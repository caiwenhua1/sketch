/*
 * Copyright (C) 2017 Peng fei Pan <sky@xiaopan.me>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.sketch.http;

import android.support.annotation.NonNull;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantLock;

import me.xiaopan.sketch.Identifier;
import me.xiaopan.sketch.SLog;
import me.xiaopan.sketch.cache.DiskCache;
import me.xiaopan.sketch.request.BaseRequest;
import me.xiaopan.sketch.request.CanceledException;
import me.xiaopan.sketch.request.DownloadRequest;
import me.xiaopan.sketch.request.DownloadResult;
import me.xiaopan.sketch.request.ErrorCause;
import me.xiaopan.sketch.request.ImageFrom;
import me.xiaopan.sketch.util.DiskLruCache;
import me.xiaopan.sketch.util.SketchUtils;

public class ImageDownloader implements Identifier {
    private static final String NAME = "ImageDownloader";

    /**
     * Download image
     *
     * @param request DownloadRequest
     * @return DownloadResult
     * @throws CanceledException canceled
     * @throws DownloadException download failed
     */
    @NonNull
    public DownloadResult download(@NonNull DownloadRequest request) throws CanceledException, DownloadException {
        DiskCache diskCache = request.getConfiguration().getDiskCache();
        String diskCacheKey = request.getDiskCacheKey();

        // 使用磁盘缓存就必须要上锁
        ReentrantLock diskCacheEditLock = null;
        if (!request.getOptions().isCacheInDiskDisabled()) {
            diskCacheEditLock = diskCache.getEditLock(diskCacheKey);
        }
        if (diskCacheEditLock != null) {
            diskCacheEditLock.lock();
        }

        try {
            if (request.isCanceled()) {
                if (SLog.isLoggable(SLog.LEVEL_DEBUG | SLog.TYPE_FLOW)) {
                    SLog.d(NAME, "Download canceled after get disk cache edit lock. %s. %s", request.getThreadName(), request.getKey());
                }
                throw new CanceledException();
            }

            if (diskCacheEditLock != null) {
                request.setStatus(BaseRequest.Status.CHECK_DISK_CACHE);
                DiskCache.Entry diskCacheEntry = diskCache.get(diskCacheKey);
                if (diskCacheEntry != null) {
                    return new DownloadResult(diskCacheEntry, ImageFrom.DISK_CACHE);
                }
            }

            return loopRetryDownload(request, diskCache, diskCacheKey);
        } finally {
            if (diskCacheEditLock != null) {
                diskCacheEditLock.unlock();
            }
        }
    }

    /**
     * Download exception retry
     *
     * @param request      DownloadRequest
     * @param diskCache    DiskCache
     * @param diskCacheKey disk cache key
     * @return DownloadResult
     * @throws CanceledException canceled
     * @throws DownloadException download failed
     */
    @NonNull
    private DownloadResult loopRetryDownload(@NonNull DownloadRequest request, @NonNull DiskCache diskCache,
                                             @NonNull String diskCacheKey) throws CanceledException, DownloadException {
        HttpStack httpStack = request.getConfiguration().getHttpStack();
        int retryCount = 0;
        final int maxRetryCount = httpStack.getMaxRetryCount();
        while (true) {
            try {
                return doDownload(request, httpStack, diskCache, diskCacheKey);
            } catch (Throwable tr) {
                request.getConfiguration().getErrorTracker().onDownloadError(request, tr);

                if (request.isCanceled()) {
                    String message = String.format("Download exception, but canceled. %s. %s", request.getThreadName(), request.getKey());
                    if (SLog.isLoggable(SLog.LEVEL_DEBUG | SLog.TYPE_FLOW)) {
                        SLog.d(NAME, tr, message);
                    }
                    throw new DownloadException(message, tr, ErrorCause.DOWNLOAD_EXCEPTION_AND_CANCELED);
                } else if (httpStack.canRetry(tr) && retryCount < maxRetryCount) {
                    tr.printStackTrace();
                    retryCount++;
                    String message = String.format("Download exception but can retry. %s. %s", request.getThreadName(), request.getKey());
                    SLog.w(NAME, tr, message);
                } else if (tr instanceof CanceledException) {
                    throw (CanceledException) tr;
                } else if (tr instanceof DownloadException) {
                    throw (DownloadException) tr;
                } else {
                    String message = String.format("Download failed. %s. %s", request.getThreadName(), request.getKey());
                    SLog.w(NAME, tr, message);
                    throw new DownloadException(message, tr, ErrorCause.DOWNLOAD_UNKNOWN_EXCEPTION);
                }
            }
        }
    }

    /**
     * Real execute download
     *
     * @param request      DownloadRequest
     * @param httpStack    HttpStack
     * @param diskCache    DiskCache
     * @param diskCacheKey disk cache key
     * @return DownloadResult
     * @throws IOException       because io
     * @throws CanceledException canceled
     * @throws DownloadException download failed
     */
    @NonNull
    private DownloadResult doDownload(@NonNull DownloadRequest request, @NonNull HttpStack httpStack,
                                      @NonNull DiskCache diskCache, @NonNull String diskCacheKey)
            throws IOException, CanceledException, DownloadException {
        // Opening http connection
        request.setStatus(BaseRequest.Status.CONNECTING);
        HttpStack.ImageHttpResponse httpResponse;
        //noinspection CaughtExceptionImmediatelyRethrown
        try {
            httpResponse = httpStack.getHttpResponse(request.getUri());
        } catch (IOException e) {
            throw e;
        }

        // Check canceled
        if (request.isCanceled()) {
            httpResponse.releaseConnection();
            if (SLog.isLoggable(SLog.LEVEL_DEBUG | SLog.TYPE_FLOW)) {
                SLog.d(NAME, "Download canceled after opening the connection. %s. %s", request.getThreadName(), request.getKey());
            }
            throw new CanceledException();
        }

        // Check response code, must be 200
        int responseCode;
        try {
            responseCode = httpResponse.getResponseCode();
        } catch (IOException e) {
            httpResponse.releaseConnection();
            String message = String.format("Get response code exception. responseHeaders: %s. %s. %s",
                    httpResponse.getResponseHeadersString(), request.getThreadName(), request.getKey());
            SLog.w(NAME, e, message);
            throw new DownloadException(message, e, ErrorCause.DOWNLOAD_GET_RESPONSE_CODE_EXCEPTION);
        }
        if (responseCode != 200) {
            httpResponse.releaseConnection();
            String message = String.format("Response code exception. responseHeaders: %s. %s. %s",
                    httpResponse.getResponseHeadersString(), request.getThreadName(), request.getKey());
            SLog.e(NAME, message);
            throw new DownloadException(message, ErrorCause.DOWNLOAD_RESPONSE_CODE_EXCEPTION);
        }

        // Check content length, must be greater than 0 or is chunked
        long contentLength = httpResponse.getContentLength();
        if (contentLength <= 0 && !httpResponse.isContentChunked()) {
            httpResponse.releaseConnection();
            String message = String.format("Content length exception. contentLength: %d, responseHeaders: %s. %s. %s",
                    contentLength, httpResponse.getResponseHeadersString(), request.getThreadName(), request.getKey());
            SLog.e(NAME, message);
            throw new DownloadException(message, ErrorCause.DOWNLOAD_CONTENT_LENGTH_EXCEPTION);
        }

        // Get content
        InputStream inputStream;
        try {
            inputStream = httpResponse.getContent();
        } catch (IOException e) {
            httpResponse.releaseConnection();
            throw e;
        }

        // Check canceled
        if (request.isCanceled()) {
            SketchUtils.close(inputStream);
            if (SLog.isLoggable(SLog.LEVEL_DEBUG | SLog.TYPE_FLOW)) {
                SLog.d(NAME, "Download canceled after get content. %s. %s", request.getThreadName(), request.getKey());
            }
            throw new CanceledException();
        }

        // Ready OutputStream, the ByteArrayOutputStream is used when the disk cache is disabled
        DiskCache.Editor diskCacheEditor = null;
        if (!request.getOptions().isCacheInDiskDisabled()) {
            diskCacheEditor = diskCache.edit(diskCacheKey);
        }
        OutputStream outputStream;
        if (diskCacheEditor != null) {
            try {
                outputStream = new BufferedOutputStream(diskCacheEditor.newOutputStream(), 8 * 1024);
            } catch (IOException e) {
                SketchUtils.close(inputStream);
                diskCacheEditor.abort();
                String message = String.format("Open disk cache exception. %s. %s", request.getThreadName(), request.getKey());
                SLog.e(NAME, e, message);
                throw new DownloadException(message, e, ErrorCause.DOWNLOAD_OPEN_DISK_CACHE_EXCEPTION);
            }
        } else {
            outputStream = new ByteArrayOutputStream();
        }

        // Read data
        request.setStatus(BaseRequest.Status.READ_DATA);
        int completedLength = 0;
        try {
            completedLength = readData(request, inputStream, outputStream, (int) contentLength);
        } catch (IOException e) {
            if (diskCacheEditor != null) {
                diskCacheEditor.abort();
                diskCacheEditor = null;
            }
            String message = String.format("Read data exception. %s. %s", request.getThreadName(), request.getKey());
            SLog.e(NAME, e, message);
            throw new DownloadException(message, e, ErrorCause.DOWNLOAD_READ_DATA_EXCEPTION);
        } catch (CanceledException e) {
            if (diskCacheEditor != null) {
                diskCacheEditor.abort();
                diskCacheEditor = null;
            }
            throw e;
        } finally {
            SketchUtils.close(outputStream);
            SketchUtils.close(inputStream);
        }

        // Check content fully and commit the disk cache
        boolean readFully = (contentLength <= 0 && !httpResponse.isContentChunked()) || completedLength == contentLength;
        if (readFully) {
            if (diskCacheEditor != null) {
                try {
                    diskCacheEditor.commit();
                } catch (IOException | DiskLruCache.EditorChangedException | DiskLruCache.ClosedException | DiskLruCache.FileNotExistException e) {
                    String message = String.format("Disk cache commit exception. %s. %s", request.getThreadName(), request.getKey());
                    SLog.e(NAME, e, message);
                    throw new DownloadException(message, e, ErrorCause.DOWNLOAD_DISK_CACHE_COMMIT_EXCEPTION);
                }
            }
        } else {
            if (diskCacheEditor != null) {
                diskCacheEditor.abort();
            }
            String message = String.format("The data is not fully read. contentLength:%d, completedLength:%d. %s. %s",
                    contentLength, completedLength, request.getThreadName(), request.getKey());
            SLog.e(NAME, message);
            throw new DownloadException(message, ErrorCause.DOWNLOAD_DATA_NOT_FULLY_READ);
        }

        // Return DownloadResult
        if (diskCacheEditor == null) {
            if (SLog.isLoggable(SLog.LEVEL_DEBUG | SLog.TYPE_FLOW)) {
                SLog.d(NAME, "Download success. Data is saved to disk cache. fileLength: %d/%d. %s. %s",
                        completedLength, contentLength, request.getThreadName(), request.getKey());
            }
            return new DownloadResult(((ByteArrayOutputStream) outputStream).toByteArray(), ImageFrom.NETWORK);
        } else {
            DiskCache.Entry diskCacheEntry = diskCache.get(diskCacheKey);
            if (diskCacheEntry != null) {
                if (SLog.isLoggable(SLog.LEVEL_DEBUG | SLog.TYPE_FLOW)) {
                    SLog.d(NAME, "Download success. data is saved to memory. fileLength: %d/%d. %s. %s",
                            completedLength, contentLength, request.getThreadName(), request.getKey());
                }
                return new DownloadResult(diskCacheEntry, ImageFrom.NETWORK);
            } else {
                String message = String.format("Not found disk cache after download success. %s. %s", request.getThreadName(), request.getKey());
                SLog.e(NAME, message);
                throw new DownloadException(message, ErrorCause.DOWNLOAD_NOT_FOUND_DISK_CACHE_AFTER_SUCCESS);
            }
        }
    }

    /**
     * Read data and call update progress
     *
     * @param request       DownloadRequest
     * @param inputStream   InputStream
     * @param outputStream  OutputStream
     * @param contentLength content length
     * @return completed length
     * @throws IOException       because io
     * @throws CanceledException canceled
     */
    private int readData(@NonNull DownloadRequest request, @NonNull InputStream inputStream,
                         @NonNull OutputStream outputStream, int contentLength) throws IOException, CanceledException {
        int realReadCount;
        int completedLength = 0;
        long lastCallbackTime = 0;
        byte[] buffer = new byte[8 * 1024];
        while (true) {
            if (request.isCanceled()) {
                if (SLog.isLoggable(SLog.LEVEL_DEBUG | SLog.TYPE_FLOW)) {
                    boolean readFully = contentLength <= 0 || completedLength == contentLength;
                    String readStatus = readFully ? "read fully" : "not read fully";
                    SLog.d(NAME, "Download canceled in read data. %s. %s. %s", readStatus, request.getThreadName(), request.getKey());
                }
                throw new CanceledException();
            }

            realReadCount = inputStream.read(buffer);
            if (realReadCount != -1) {
                outputStream.write(buffer, 0, realReadCount);
                completedLength += realReadCount;

                // Update progress every 100 milliseconds
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastCallbackTime >= 100) {
                    lastCallbackTime = currentTime;
                    request.updateProgress(contentLength, completedLength);
                }
            } else {
                // The end of the time to call back the progress of the time to ensure that the page can display 100%
                request.updateProgress(contentLength, completedLength);
                break;
            }
        }
        outputStream.flush();
        return completedLength;
    }

    @NonNull
    @Override
    public String getKey() {
        return NAME;
    }
}
