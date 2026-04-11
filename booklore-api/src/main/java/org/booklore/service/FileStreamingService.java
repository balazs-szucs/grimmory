package org.booklore.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Slf4j
@Service
public class FileStreamingService {

    /**
     * Streams a file with HTTP Range support for seeking.
     * Uses Java NIO FileChannel for high performance and zero-copy transfer where possible.
     */
    public void streamWithRangeSupport(
            Path filePath,
            String contentType,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        if (!Files.exists(filePath)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found");
            return;
        }

        long fileSize = Files.size(filePath);
        String rangeHeader = request.getHeader("Range");

        // Set standard headers for media streaming
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("Content-Disposition", "inline");
        response.setContentType(contentType);

        // -------------------------
        // HEAD request support
        // -------------------------
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes 0-" + (fileSize - 1) + "/" + fileSize);
            response.setContentLengthLong(fileSize);
            return;
        }

        try (FileChannel fileChannel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            // -------------------------
            // NO RANGE — Full file
            // -------------------------
            if (rangeHeader == null) {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentLengthLong(fileSize);
                transferFile(fileChannel, 0, fileSize, response.getOutputStream());
                return;
            }

            // -------------------------
            // RANGE — Partial content
            // -------------------------
            Range range = parseRange(rangeHeader, fileSize);
            if (range == null) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader("Content-Range", "bytes */" + fileSize);
                return;
            }

            long length = range.end - range.start + 1;
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + range.start + "-" + range.end + "/" + fileSize);
            response.setContentLengthLong(length);

            transferFile(fileChannel, range.start, length, response.getOutputStream());

        } catch (IOException e) {
            if (isClientDisconnect(e)) {
                log.debug("Client disconnected during streaming: {}", e.getMessage());
            } else {
                log.error("Error during file streaming: {}", filePath, e);
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Streaming error");
                }
            }
        }
    }

    /**
     * Efficiently transfers data from the file channel to the output stream.
     * Uses zero-copy if supported by the operating system.
     */
    private void transferFile(FileChannel source, long position, long count, OutputStream out) throws IOException {
        try (WritableByteChannel destination = Channels.newChannel(out)) {
            long remaining = count;
            long currentPos = position;
            
            while (remaining > 0) {
                // transferTo can transfer up to 2GB at once on some platforms
                long transferred = source.transferTo(currentPos, remaining, destination);
                if (transferred <= 0) break;
                currentPos += transferred;
                remaining -= transferred;
            }
        }
    }

    // ------------------------------------------------------------
    // RANGE PARSER — RFC 7233 compliant
    // ------------------------------------------------------------
    Range parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=")) {
            return null;
        }

        String value = header.substring(6).trim();
        String[] parts = value.split(",", 2);
        String range = parts[0].trim();

        int dash = range.indexOf('-');
        if (dash < 0) return null;

        try {
            // suffix-byte-range-spec: "-<length>"
            if (dash == 0) {
                long suffix = Long.parseLong(range.substring(1));
                if (suffix <= 0) return null;
                suffix = Math.min(suffix, size);
                return new Range(size - suffix, size - 1);
            }

            long start = Long.parseLong(range.substring(0, dash));

            // open-ended: "<start>-"
            if (dash == range.length() - 1) {
                if (start >= size) return null;
                return new Range(start, size - 1);
            }

            // "<start>-<end>"
            long end = Long.parseLong(range.substring(dash + 1));
            if (start > end || start >= size) return null;
            end = Math.min(end, size - 1);

            return new Range(start, end);

        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------
    // DISCONNECT DETECTION
    // ------------------------------------------------------------
    boolean isClientDisconnect(IOException e) {
        if (e instanceof SocketTimeoutException) return true;

        String msg = e.getMessage();
        if (msg == null) return false;

        return msg.contains("Broken pipe")
                || msg.contains("Connection reset")
                || msg.contains("connection was aborted")
                || msg.contains("An established connection was aborted")
                || msg.contains("SocketTimeout")
                || msg.contains("timed out");
    }

    record Range(long start, long end) {}
}