package com.basrikahveci.p2p.file;

/**
 * Definición de los mensajes para el protocolo de transferencia de archivos
 */
public class FileProtocol {

    /**
     * Tipos de mensajes para la transferencia de archivos
     */
    public enum MessageType {
        // Inicio de transferencia con metadatos del archivo
        FILE_METADATA,

        // Fragmento de datos del archivo
        FILE_CHUNK,

        // Confirmación de recepción de fragmento
        CHUNK_ACK,

        // Finalización exitosa de la transferencia
        TRANSFER_COMPLETE,

        // Cancelación de la transferencia
        TRANSFER_CANCEL,

        // Error durante la transferencia
        TRANSFER_ERROR
    }

    /**
     * Mensaje base para la transferencia de archivos
     */
    public static class FileMessage {
        private final MessageType type;
        private final String transferId;
        private final String sourcePeer;

        public FileMessage(MessageType type, String transferId, String sourcePeer) {
            this.type = type;
            this.transferId = transferId;
            this.sourcePeer = sourcePeer;
        }

        public MessageType getType() {
            return type;
        }

        public String getTransferId() {
            return transferId;
        }

        public String getSourcePeer() {
            return sourcePeer;
        }
    }

    /**
     * Mensaje de inicio de transferencia con metadatos del archivo
     */
    public static class FileMetadataMessage extends FileMessage {
        private final String fileName;
        private final String fullPath;
        private final long fileSize;

        public FileMetadataMessage(String transferId, String sourcePeer, String fileName,String fullPath, long fileSize) {
            super(MessageType.FILE_METADATA, transferId, sourcePeer);
            this.fileName = fileName;
            this.fullPath = fullPath;
            this.fileSize = fileSize;
        }

        public String getFileName() {
            return fileName;
        }

        public String getFullPath() {
            return fullPath;
        }

        public long getFileSize() {
            return fileSize;
        }
    }

    /**
     * Mensaje con un fragmento de datos del archivo
     */
    public static class FileChunkMessage extends FileMessage {
        private final byte[] data;
        private final long offset;
        private final int chunkNumber;

        public FileChunkMessage(String transferId, String sourcePeer, byte[] data, long offset, int chunkNumber) {
            super(MessageType.FILE_CHUNK, transferId, sourcePeer);
            this.data = data;
            this.offset = offset;
            this.chunkNumber = chunkNumber;
        }

        public byte[] getData() {
            return data;
        }

        public long getOffset() {
            return offset;
        }

        public int getChunkNumber() {
            return chunkNumber;
        }
    }

    /**
     * Mensaje de confirmación de recepción de fragmento
     */
    public static class ChunkAckMessage extends FileMessage {
        private final int chunkNumber;

        public ChunkAckMessage(String transferId, String sourcePeer, int chunkNumber) {
            super(MessageType.CHUNK_ACK, transferId, sourcePeer);
            this.chunkNumber = chunkNumber;
        }

        public int getChunkNumber() {
            return chunkNumber;
        }
    }

    /**
     * Mensaje de finalización de transferencia
     */
    public static class TransferCompleteMessage extends FileMessage {
        private final long totalBytes;

        public TransferCompleteMessage(String transferId, String sourcePeer, long totalBytes) {
            super(MessageType.TRANSFER_COMPLETE, transferId, sourcePeer);
            this.totalBytes = totalBytes;
        }

        public long getTotalBytes() {
            return totalBytes;
        }
    }

    /**
     * Mensaje de cancelación de transferencia
     */
    public static class TransferCancelMessage extends FileMessage {
        private final String reason;

        public TransferCancelMessage(String transferId, String sourcePeer, String reason) {
            super(MessageType.TRANSFER_CANCEL, transferId, sourcePeer);
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * Mensaje de error en la transferencia
     */
    public static class TransferErrorMessage extends FileMessage {
        private final String errorMessage;

        public TransferErrorMessage(String transferId, String sourcePeer, String errorMessage) {
            super(MessageType.TRANSFER_ERROR, transferId, sourcePeer);
            this.errorMessage = errorMessage;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}