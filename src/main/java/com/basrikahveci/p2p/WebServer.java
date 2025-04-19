package com.basrikahveci.p2p;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;

public class WebServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebServer.class);

    private final PeerRunner peerRunner;
    private final int port;
    private HttpServer server;

    public WebServer(PeerRunner peerRunner, int port) {
        this.peerRunner = peerRunner;
        this.port = port;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new DashboardHandler());
            server.createContext("/command", new CommandHandler());
            server.createContext("/upload", new FileUploadHandler());
            server.createContext("/files", new FileListHandler());
            server.createContext("/download", new FileDownloadHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            LOGGER.info("Web server started on port {}", port);
        } catch (IOException e) {
            LOGGER.error("Failed to start web server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            LOGGER.info("Web server stopped");
        }
    }

    // Handler for main dashboard page
    private class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = generateDashboardHtml();
            sendResponse(exchange, 200, "text/html", html);
        }
    }

    // Handler for command execution
    private class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            Map<String, String> params = parseFormData(exchange);
            String command = params.get("command");

            if (command == null || command.trim().isEmpty()) {
                sendResponse(exchange, 400, "text/plain", "Missing command");
                return;
            }

            // Execute command and capture output
            StringWriter output = new StringWriter();
            PrintWriter printWriter = new PrintWriter(output);

            // Redirect System.out temporarily
            PrintStream originalOut = System.out;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            System.setOut(new PrintStream(baos));

            try {
                PeerRunner.CommandResult result = peerRunner.handleCommand(command);

                if (result == PeerRunner.CommandResult.INVALID_COMMAND) {
                    printWriter.println("Invalid command: " + command);
                }

                // Get command output
                String consoleOutput = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                printWriter.print(consoleOutput);

            } finally {
                // Restore original System.out
                System.setOut(originalOut);
            }

            sendResponse(exchange, 200, "text/plain", output.toString());
        }
    }

    // Handler for file uploading to send to peers
    private class FileUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            // Parse multipart form data to get file and target peer
            try {
                Map<String, Object> formData = parseMultipartFormData(exchange);
                String targetPeer = (String) formData.get("targetPeer");
                byte[] fileData = (byte[]) formData.get("fileData");
                String fileName = (String) formData.get("fileName");

                if (targetPeer == null || fileData == null || fileName == null) {
                    sendResponse(exchange, 400, "text/plain", "Missing required form fields");
                    return;
                }

                // Save file temporarily
                File tempFile = new File("./temp_" + fileName);
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(fileData);
                }

                // Send file to peer
                peerRunner.getHandle().sendFile(targetPeer, tempFile);

                // Delete temp file after sending
                tempFile.delete();

                sendResponse(exchange, 200, "text/plain", "File upload initiated successfully");

            } catch (Exception e) {
                LOGGER.error("Error processing file upload", e);
                sendResponse(exchange, 500, "text/plain", "Error processing file upload: " + e.getMessage());
            }
        }
    }

    // Handler for listing received files
    private class FileListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
        File uploadDir = new File("./uploads/"+peerRunner.getHandle().getPeerName());
            StringBuilder response = new StringBuilder();

            if (uploadDir.exists() && uploadDir.isDirectory()) {
                File[] files = uploadDir.listFiles();
                if (files != null && files.length > 0) {
                    for (File file : files) {
                        if (file.isFile() && !file.getName().contains("_")) { // Exclude temp files
                            response.append(file.getName())
                                    .append("|")
                                    .append(file.length())
                                    .append("\n");
                        }
                    }
                }
            }

            sendResponse(exchange, 200, "text/plain", response.toString());
        }
    }

    // Handler for downloading received files
    private class FileDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
            String fileName = params.get("file");

            if (fileName == null || fileName.trim().isEmpty()) {
                sendResponse(exchange, 400, "text/plain", "Missing file parameter");
                return;
            }

            File uploadDir = new File("./uploads/peer11111");
            File fileToDownload = new File(uploadDir, fileName);

            if (!fileToDownload.exists() || !fileToDownload.isFile()) {
                sendResponse(exchange, 404, "text/plain", "File not found");
                return;
            }

            exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, fileToDownload.length());

            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(fileToDownload)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    // Helper methods
    private String generateDashboardHtml() {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <title>P2P Network Dashboard</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; margin: 0; padding: 20px; }\n" +
                "        h1 { color: #2c3e50; }\n" +
                "        .container { display: flex; }\n" +
                "        .panel { flex: 1; margin: 10px; padding: 15px; border: 1px solid #ddd; border-radius: 5px; }\n" +
                "        .terminal { background-color: #2c3e50; color: #ecf0f1; padding: 10px; border-radius: 5px; height: 300px; overflow-y: auto; font-family: monospace; }\n" +
                "        input[type=text], input[type=number], select { width: 100%; padding: 8px; margin: 5px 0; display: inline-block; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box; }\n" +
                "        button { background-color: #4CAF50; color: white; padding: 10px 15px; margin: 8px 0; border: none; border-radius: 4px; cursor: pointer; }\n" +
                "        button:hover { background-color: #45a049; }\n" +
                "        .file-list { height: 200px; overflow-y: auto; border: 1px solid #ddd; padding: 10px; margin-top: 10px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <h1>P2P Network Dashboard - " + peerRunner.getHandle().getPeerName() + "</h1>\n" +
                "    <div class=\"container\">\n" +
                "        <div class=\"panel\">\n" +
                "            <h2>Command Console</h2>\n" +
                "            <div class=\"terminal\" id=\"terminal\"></div>\n" +
                "            <div style=\"display: flex; margin-top: 10px;\">\n" +
                "                <input type=\"text\" id=\"commandInput\" placeholder=\"Enter command...\" style=\"flex: 1;\">\n" +
                "                <button onclick=\"executeCommand()\" style=\"margin-left: 10px;\">Execute</button>\n" +
                "            </div>\n" +
                "            <div style=\"margin-top: 10px;\">\n" +
                "                <button onclick=\"executePredefinedCommand('ping')\">Ping</button>\n" +
                "                <button onclick=\"executePredefinedCommand('election')\">Election</button>\n" +
                "                <button onclick=\"executePredefinedCommand('files')\">List Files</button>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "        <div class=\"panel\">\n" +
                "            <h2>Connect to Peer</h2>\n" +
                "            <input type=\"text\" id=\"connectHost\" placeholder=\"Host\">\n" +
                "            <input type=\"number\" id=\"connectPort\" placeholder=\"Port\">\n" +
                "            <button onclick=\"connect()\">Connect</button>\n" +
                "            \n" +
                "            <h2>Disconnect from Peer</h2>\n" +
                "            <input type=\"text\" id=\"disconnectPeer\" placeholder=\"Peer Name\">\n" +
                "            <button onclick=\"disconnect()\">Disconnect</button>\n" +
                "            \n" +
                "            <h2>Send File</h2>\n" +
                "            <form id=\"uploadForm\" enctype=\"multipart/form-data\">\n" +
                "                <input type=\"text\" id=\"targetPeer\" name=\"targetPeer\" placeholder=\"Target Peer\">\n" +
                "                <input type=\"file\" id=\"fileInput\" name=\"file\">\n" +
                "                <button type=\"button\" onclick=\"uploadFile()\">Send File</button>\n" +
                "            </form>\n" +
                "        </div>\n" +
                "        <div class=\"panel\">\n" +
                "            <h2>Received Files</h2>\n" +
                "            <button onclick=\"refreshFiles()\">Refresh</button>\n" +
                "            <div class=\"file-list\" id=\"fileList\"></div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <script>\n" +
                "        // Load files on page load\n" +
                "        window.onload = function() {\n" +
                "            refreshFiles();\n" +
                "            appendToTerminal('Welcome to P2P Network Dashboard. Type commands or use buttons.');\n" +
                "        };\n" +
                "        \n" +
                "        function executeCommand() {\n" +
                "            const command = document.getElementById('commandInput').value;\n" +
                "            if (!command) return;\n" +
                "            \n" +
                "            appendToTerminal('> ' + command);\n" +
                "            document.getElementById('commandInput').value = '';\n" +
                "            \n" +
                "            fetch('/command', {\n" +
                "                method: 'POST',\n" +
                "                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },\n" +
                "                body: 'command=' + encodeURIComponent(command)\n" +
                "            })\n" +
                "            .then(response => response.text())\n" +
                "            .then(data => {\n" +
                "                appendToTerminal(data);\n" +
                "                if (command === 'ping' || command === 'files') refreshFiles();\n" +
                "            })\n" +
                "            .catch(error => appendToTerminal('Error: ' + error));\n" +
                "        }\n" +
                "        \"        function executePredefinedCommand(command) {\n" +
                "            document.getElementById('commandInput').value = command;\n" +
                "            executeCommand();\n" +
                "        }\n" +
                "        \n" +
                "        function connect() {\n" +
                "            const host = document.getElementById('connectHost').value;\n" +
                "            const port = document.getElementById('connectPort').value;\n" +
                "            if (!host || !port) return;\n" +
                "            \n" +
                "            const command = 'connect ' + host + ' ' + port;\n" +
                "            document.getElementById('commandInput').value = command;\n" +
                "            executeCommand();\n" +
                "        }\n" +
                "        \n" +
                "        function disconnect() {\n" +
                "            const peer = document.getElementById('disconnectPeer').value;\n" +
                "            if (!peer) return;\n" +
                "            \n" +
                "            const command = 'disconnect ' + peer;\n" +
                "            document.getElementById('commandInput').value = command;\n" +
                "            executeCommand();\n" +
                "        }\n" +
                "        \n" +
                "        function uploadFile() {\n" +
                "            const targetPeer = document.getElementById('targetPeer').value;\n" +
                "            const fileInput = document.getElementById('fileInput');\n" +
                "            \n" +
                "            if (!targetPeer || !fileInput.files[0]) {\n" +
                "                appendToTerminal('Error: Please select a file and specify target peer');\n" +
                "                return;\n" +
                "            }\n" +
                "            \n" +
                "            const formData = new FormData();\n" +
                "            formData.append('targetPeer', targetPeer);\n" +
                "            formData.append('file', fileInput.files[0]);\n" +
                "            \n" +
                "            appendToTerminal('Uploading file ' + fileInput.files[0].name + ' to peer ' + targetPeer + '...');\n" +
                "            \n" +
                "            fetch('/upload', {\n" +
                "                method: 'POST',\n" +
                "                body: formData\n" +
                "            })\n" +
                "            .then(response => response.text())\n" +
                "            .then(data => {\n" +
                "                appendToTerminal(data);\n" +
                "                // Clear the file input\n" +
                "                fileInput.value = '';\n" +
                "            })\n" +
                "            .catch(error => appendToTerminal('Error: ' + error));\n" +
                "        }\n" +
                "        \n" +
                "        function refreshFiles() {\n" +
                "            fetch('/files')\n" +
                "            .then(response => response.text())\n" +
                "            .then(data => {\n" +
                "                const fileList = document.getElementById('fileList');\n" +
                "                fileList.innerHTML = '';\n" +
                "                \n" +
                "                if (!data.trim()) {\n" +
                "                    fileList.innerHTML = '<p>No files received yet.</p>';\n" +
                "                    return;\n" +
                "                }\n" +
                "                \n" +
                "                const files = data.trim().split('\\n');\n" +
                "                for (const file of files) {\n" +
                "                    const [name, size] = file.split('|');\n" +
                "                    const fileDiv = document.createElement('div');\n" +
                "                    fileDiv.innerHTML = `\n" +
                "                        <div style=\"display: flex; justify-content: space-between; align-items: center; margin-bottom: 5px;\">\n" +
                "                            <span>${name} (${formatBytes(size)})</span>\n" +
                "                            <a href=\"/download?file=${encodeURIComponent(name)}\" download=\"${name}\">\n" +
                "                                <button>Download</button>\n" +
                "                            </a>\n" +
                "                        </div>\n" +
                "                    `;\n" +
                "                    fileList.appendChild(fileDiv);\n" +
                "                }\n" +
                "            })\n" +
                "            .catch(error => console.error('Error refreshing files:', error));\n" +
                "        }\n" +
                "        \n" +
                "        function formatBytes(bytes, decimals = 2) {\n" +
                "            if (bytes === 0) return '0 Bytes';\n" +
                "            const k = 1024;\n" +
                "            const dm = decimals < 0 ? 0 : decimals;\n" +
                "            const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];\n" +
                "            const i = Math.floor(Math.log(bytes) / Math.log(k));\n" +
                "            return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];\n" +
                "        }\n" +
                "        \n" +
                "        function appendToTerminal(text) {\n" +
                "            const terminal = document.getElementById('terminal');\n" +
                "            const lines = text.split('\\n');\n" +
                "            for (const line of lines) {\n" +
                "                if (line.trim()) {\n" +
                "                    const p = document.createElement('p');\n" +
                "                    p.style.margin = '2px 0';\n" +
                "                    p.textContent = line;\n" +
                "                    terminal.appendChild(p);\n" +
                "                }\n" +
                "            }\n" +
                "            terminal.scrollTop = terminal.scrollHeight;\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> parseFormData(HttpExchange exchange) throws IOException {
        Map<String, String> parameters = new HashMap<>();

        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        String formData = br.readLine();

        if (formData != null) {
            String[] pairs = formData.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    parameters.put(keyValue[0], java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8.name()));
                }
            }
        }

        return parameters;
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }

        for (String param : query.split("&")) {
            String[] keyValue = param.split("=");
            if (keyValue.length == 2) {
                try {
                    params.put(keyValue[0], java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8.name()));
                } catch (UnsupportedEncodingException e) {
                    LOGGER.error("Error decoding query parameter", e);
                }
            }
        }

        return params;
    }

    private Map<String, Object> parseMultipartFormData(HttpExchange exchange) throws IOException {
        Map<String, Object> formData = new HashMap<>();

        // Get content type and boundary
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            throw new IOException("Expected multipart/form-data request");
        }

        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }

        // Convert the boundary to byte patterns
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        byte[] endBoundaryBytes = ("--" + boundary + "--").getBytes(StandardCharsets.UTF_8);

        // Read the entire request body into memory
        ByteArrayOutputStream requestBodyOutStream = new ByteArrayOutputStream();
        InputStream requestBodyInStream = exchange.getRequestBody();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = requestBodyInStream.read(buffer)) != -1) {
            requestBodyOutStream.write(buffer, 0, bytesRead);
        }
        byte[] requestBody = requestBodyOutStream.toByteArray();

        // Process each part of the multipart data
        int currentPosition = 0;
        int boundaryPosition;

        // Find first boundary
        boundaryPosition = findSequence(requestBody, boundaryBytes, currentPosition);
        if (boundaryPosition == -1) {
            throw new IOException("Invalid multipart form data: missing initial boundary");
        }

        currentPosition = boundaryPosition + boundaryBytes.length + 2; // +2 for CRLF

        // Process parts until we reach the end boundary
        while (true) {
            // Find the next boundary
            boundaryPosition = findSequence(requestBody, boundaryBytes, currentPosition);
            int endBoundaryPosition = findSequence(requestBody, endBoundaryBytes, currentPosition);

            if (boundaryPosition == -1 && endBoundaryPosition == -1) {
                break; // No more boundaries found
            }

            // Determine the end of the current part
            int partEnd;
            if (endBoundaryPosition != -1 && (boundaryPosition == -1 || endBoundaryPosition < boundaryPosition)) {
                partEnd = endBoundaryPosition - 2; // -2 for CRLF before boundary
                // We've reached the end boundary
                boundaryPosition = -1;
            } else {
                partEnd = boundaryPosition - 2; // -2 for CRLF before boundary
            }

            // Process the current part
            processFormPart(requestBody, currentPosition, partEnd, formData);

            // Move to the next part or exit if we've reached the end boundary
            if (boundaryPosition == -1) {
                break;
            }
            currentPosition = boundaryPosition + boundaryBytes.length + 2; // +2 for CRLF
        }

        return formData;
    }

    private void processFormPart(byte[] data, int start, int end, Map<String, Object> formData) throws IOException {
        // Find the end of the headers section (double CRLF)
        int headersEnd = findSequence(data, new byte[]{'\r', '\n', '\r', '\n'}, start);
        if (headersEnd == -1) {
            throw new IOException("Invalid form part: no headers found");
        }

        // Extract and parse headers
        String headersString = new String(data, start, headersEnd - start, StandardCharsets.UTF_8);
        Map<String, String> headers = parseHeaders(headersString);

        // Content starts after headers + CRLFCRLF
        int contentStart = headersEnd + 4;
        int contentLength = end - contentStart;

        // Process content based on Content-Disposition header
        String contentDisposition = headers.get("Content-Disposition");
        if (contentDisposition != null) {
            // Extract field name
            String fieldName = extractFieldName(contentDisposition);

            // Check if this is a file upload
            String filename = extractFilename(contentDisposition);

            if (filename != null && !filename.isEmpty()) {
                // This is a file upload
                byte[] fileData = new byte[contentLength];
                System.arraycopy(data, contentStart, fileData, 0, contentLength);

                formData.put("fileData", fileData);
                formData.put("fileName", filename);
            } else {
                // This is a regular form field
                String fieldValue = new String(data, contentStart, contentLength, StandardCharsets.UTF_8);
                formData.put(fieldName, fieldValue);
            }
        }
    }

    private Map<String, String> parseHeaders(String headers) {
        Map<String, String> result = new HashMap<>();
        String[] lines = headers.split("\r\n");

        for (String line : lines) {
            int colonPos = line.indexOf(':');
            if (colonPos > 0) {
                String name = line.substring(0, colonPos).trim();
                String value = line.substring(colonPos + 1).trim();
                result.put(name, value);
            }
        }

        return result;
    }

    private String extractFieldName(String contentDisposition) {
        int nameStart = contentDisposition.indexOf("name=\"");
        if (nameStart != -1) {
            nameStart += 6; // length of 'name="'
            int nameEnd = contentDisposition.indexOf("\"", nameStart);
            if (nameEnd != -1) {
                return contentDisposition.substring(nameStart, nameEnd);
            }
        }
        return null;
    }

    private String extractFilename(String contentDisposition) {
        int filenameStart = contentDisposition.indexOf("filename=\"");
        if (filenameStart != -1) {
            filenameStart += 10; // length of 'filename="'
            int filenameEnd = contentDisposition.indexOf("\"", filenameStart);
            if (filenameEnd != -1) {
                return contentDisposition.substring(filenameStart, filenameEnd);
            }
        }
        return null;
    }

    private int findSequence(byte[] data, byte[] sequence, int startPosition) {
        for (int i = startPosition; i <= data.length - sequence.length; i++) {
            boolean found = true;
            for (int j = 0; j < sequence.length; j++) {
                if (data[i + j] != sequence[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }
}