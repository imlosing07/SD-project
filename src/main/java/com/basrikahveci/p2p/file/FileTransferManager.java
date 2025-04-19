package com.basrikahveci.p2p.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.basrikahveci.p2p.peer.PeerHandle;

public class FileTransferManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileTransferManager.class);

    private final String uploadDirectory;
    private final PeerHandle peerHandle;
    private final ConcurrentMap<String, FileTransferInfo> incomingTransfers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, FileTransferInfo> outgoingTransfers = new ConcurrentHashMap<>();

    public FileTransferManager(PeerHandle peerHandle, String uploadDirectory) {
        this.peerHandle = peerHandle;
        this.uploadDirectory = uploadDirectory;

        // Ensure upload directory exists
        File dir = new File(uploadDirectory);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public CompletableFuture<Void> sendFile(String targetPeer, File file) {
        try {
            if (!file.exists() || !file.isFile()) {
                throw new IllegalArgumentException("File does not exist: " + file.getPath());
            }

            String transferId = UUID.randomUUID().toString();
            FileTransferInfo transferInfo = new FileTransferInfo(transferId, file.getName(), file.getAbsolutePath(), // <-- PASAMOS LA RUTA COMPLETA
                    file.length());
            outgoingTransfers.put(transferId, transferInfo);

            // First send file metadata to initiate transfer
            CompletableFuture<Void> metadataFuture = peerHandle.sendFileMetadata(targetPeer, transferId, file.getName(), file.getAbsolutePath(), file.length());

            // Then start sending chunks
            metadataFuture.thenRun(() -> {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[transferInfo.getChunkSize()];
                    int bytesRead;
                    long offset = 0;
                    int chunkNumber = 0;

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] chunk = bytesRead == buffer.length ? buffer : new byte[bytesRead];
                        if (bytesRead < buffer.length) {
                            System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                        }

                        long currentOffset = offset;
                        int currentChunkNumber = chunkNumber;

                        transferInfo.getChunkTracker().markSent(currentChunkNumber);
                        peerHandle.sendFileChunk(targetPeer, transferId, chunk, currentOffset, currentChunkNumber);

                        offset += bytesRead;
                        chunkNumber++;
                    }

                    // Scheduled task to check for unacknowledged chunks and resend them
                    scheduleChunkRetransmission(targetPeer, transferInfo);

                } catch (IOException e) {
                    LOGGER.error("Error sending file " + file.getName(), e);
                    peerHandle.cancelFileTransfer(targetPeer, transferId);
                    transferInfo.getTransferFuture().completeExceptionally(e);
                }
            });

            return transferInfo.getTransferFuture();
        } catch (Exception e) {
            CompletableFuture<Void> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }

    private void scheduleChunkRetransmission(String targetPeer, FileTransferInfo transferInfo) {
        // Esta implementación es simplificada. En una implementación real,
        // deberías usar un ScheduledExecutorService para manejar los reenvíos

        Thread retransmissionThread = new Thread(() -> {
            File file = new File(transferInfo.getFullFilePath());  // <-- USAR LA RUTA COMPLETA
            ChunkTracker tracker = transferInfo.getChunkTracker();
            String transferId = transferInfo.getTransferId();

            try {
                int maxRetries = 3;
                int retryCount = 0;

                while (!tracker.isComplete() && retryCount < maxRetries && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(5000); // Esperar 5 segundos

                    // Si ya se completó en otro lugar, salir
                    if (transferInfo.isCompleted()) {
                        return;
                    }

                    int[] unacknowledgedChunks = tracker.getUnacknowledgedChunks();
                    if (unacknowledgedChunks.length > 0) {
                        LOGGER.info("Retransmitting {} unacknowledged chunks for transfer {}", unacknowledgedChunks.length, transferId);

                        // Reenviar chunks no confirmados
                        try (FileInputStream fis = new FileInputStream(file)) {
                            byte[] buffer = new byte[transferInfo.getChunkSize()];

                            for (int chunkNumber : unacknowledgedChunks) {
                                long offset = (long) chunkNumber * transferInfo.getChunkSize();
                                fis.getChannel().position(offset);

                                int bytesRead = fis.read(buffer);
                                if (bytesRead > 0) {
                                    byte[] chunk = bytesRead == buffer.length ? buffer : Arrays.copyOf(buffer, bytesRead);

                                    peerHandle.sendFileChunk(targetPeer, transferId, chunk, offset, chunkNumber);
                                    tracker.markSent(chunkNumber);
                                }
                            }
                        } catch (IOException e) {
                            LOGGER.error("Error during chunk retransmission", e);
                        }

                        retryCount++;
                    } else {
                        // Todos los chunks confirmados
                        transferInfo.setCompleted(true);
                        peerHandle.completeFileTransfer(targetPeer, transferId);
                        outgoingTransfers.remove(transferId);
                        return;
                    }
                }

                // Si después de los reintentos aún hay chunks no confirmados, cancelar
                if (!tracker.isComplete()) {
                    LOGGER.warn("Transfer {} failed after {} retries", transferId, maxRetries);
                    transferInfo.getTransferFuture().completeExceptionally(new IOException("Failed to complete transfer after " + maxRetries + " retries"));
                    peerHandle.cancelFileTransfer(targetPeer, transferId);
                    outgoingTransfers.remove(transferId);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        retransmissionThread.setDaemon(true);
        retransmissionThread.start();
    }

    public void handleFileMetadata(String sourcePeer, String transferId, String fileName, long fileSize) {
        FileTransferInfo transferInfo = new FileTransferInfo(transferId, fileName, fileSize);
        transferInfo.setTempFile(new File(uploadDirectory, transferId + "_" + fileName));
        incomingTransfers.put(transferId, transferInfo);

        LOGGER.info("Incoming file transfer started: {} from {}, size: {} bytes", fileName, sourcePeer, fileSize);
    }

    public void handleFileChunk(String sourcePeer, String transferId, byte[] data, long offset, int chunkNumber) {
        FileTransferInfo transferInfo = incomingTransfers.get(transferId);
        if (transferInfo == null) {
            LOGGER.warn("Received chunk for unknown transfer: {}", transferId);
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(transferInfo.getTempFile(), true)) {
            fos.getChannel().position(offset);
            fos.write(data);
            transferInfo.setBytesReceived(transferInfo.getBytesReceived() + data.length);

            // Log progress periodically
            if (transferInfo.getBytesReceived() % (1024 * 1024) < data.length) { // Log every ~1MB
                LOGGER.info("Transfer progress: {} - {}/{} bytes ({}%)", transferId, transferInfo.getBytesReceived(), transferInfo.getFileSize(), (transferInfo.getBytesReceived() * 100 / transferInfo.getFileSize()));
            }

            // Enviar confirmación de recepción del chunk
            peerHandle.sendChunkAcknowledgment(sourcePeer, transferId, chunkNumber);

        } catch (IOException e) {
            LOGGER.error("Error writing file chunk for transfer " + transferId, e);
        }
    }

    /**
     * Maneja la confirmación de recepción de un chunk
     *
     * @param sourcePeer  Peer que envió la confirmación
     * @param transferId  ID de la transferencia
     * @param chunkNumber Número del chunk confirmado
     */
    public void handleChunkAcknowledgment(String sourcePeer, String transferId, int chunkNumber) {
        FileTransferInfo transferInfo = outgoingTransfers.get(transferId);
        if (transferInfo == null) {
            LOGGER.warn("Received chunk acknowledgment for unknown transfer: {}", transferId);
            return;
        }

        // Marcar el chunk como recibido correctamente
        transferInfo.markChunkAcknowledged(chunkNumber);

        // Opcional: Registrar progreso
        LOGGER.debug("Chunk {} acknowledged for transfer {}", chunkNumber, transferId);

        // Si todos los chunks han sido confirmados, podemos finalizar la transferencia
        if (transferInfo.areAllChunksAcknowledged()) {
            completeFileTransfer(sourcePeer, transferId);
        }
    }

    public void completeFileTransfer(String sourcePeer, String transferId) {
        FileTransferInfo transferInfo = incomingTransfers.remove(transferId);
        if (transferInfo == null) {
            LOGGER.warn("Received completion for unknown transfer: {}", transferId);
            return;
        }

        // Rename temp file to final name
        File finalFile = new File(uploadDirectory, transferInfo.getFileName());
        if (transferInfo.getTempFile().renameTo(finalFile)) {
            LOGGER.info("File transfer completed: {} from {}, saved as {}", transferInfo.getFileName(), sourcePeer, finalFile.getAbsolutePath());
        } else {
            LOGGER.error("Failed to rename temp file to final name: {} -> {}", transferInfo.getTempFile().getPath(), finalFile.getPath());
        }
    }


    /**
     * Obtiene información sobre una transferencia saliente
     *
     * @param transferId ID de la transferencia
     * @return información de la transferencia, o null si no existe
     */
    public FileTransferInfo getOutgoingTransfer(String transferId) {
        return outgoingTransfers.get(transferId);
    }

    public void cancelFileTransfer(String sourcePeer, String transferId) {
        FileTransferInfo transferInfo = incomingTransfers.remove(transferId);
        if (transferInfo != null && transferInfo.getTempFile() != null) {
            transferInfo.getTempFile().delete();
            LOGGER.info("File transfer cancelled: {} from {}", transferInfo.getFileName(), sourcePeer);
        }
    }

    public static class FileTransferInfo {
        private final String transferId;
        private final String fileName;
        private final String fullFilePath;  // <-- NUEVA VARIABLE
        private final long fileSize;
        private File tempFile;
        private long bytesReceived = 0;
        private ChunkTracker chunkTracker;
        private final int chunkSize = 1024 * 64; // 64KB chunks
        private boolean isCompleted = false;
        private CompletableFuture<Void> transferFuture;
        private Set<Integer> acknowledgedChunks = new HashSet<>();
        private int totalChunks;

        public FileTransferInfo(String transferId, String fileName, String fullFilePath, long fileSize) {
            this.transferId = transferId;
            this.fileName = fileName;
            this.fullFilePath = fullFilePath;
            this.fileSize = fileSize;
            this.totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
            this.chunkTracker = new ChunkTracker(totalChunks);
            this.transferFuture = new CompletableFuture<>();
        }

        public FileTransferInfo(String transferId, String fileName, long fileSize) {
            this(transferId, fileName, null, fileSize); // Pasamos null como fullFilePath
        }

        public void markChunkAcknowledged(int chunkNumber) {
            acknowledgedChunks.add(chunkNumber);
            chunkTracker.markAcknowledged(chunkNumber);
        }

        public boolean isChunkAcknowledged(int chunkNumber) {
            return acknowledgedChunks.contains(chunkNumber);
        }

        public boolean areAllChunksAcknowledged() {
            return acknowledgedChunks.size() == totalChunks;
        }

        public void setTotalChunks(int totalChunks) {
            this.totalChunks = totalChunks;
        }

        public String getFullFilePath() {
            return fullFilePath;
        }

        public String getTransferId() {
            return transferId;
        }

        public String getFileName() {
            return fileName;
        }

        public long getFileSize() {
            return fileSize;
        }

        public File getTempFile() {
            return tempFile;
        }

        public void setTempFile(File tempFile) {
            this.tempFile = tempFile;
        }

        public long getBytesReceived() {
            return bytesReceived;
        }

        public void setBytesReceived(long bytesReceived) {
            this.bytesReceived = bytesReceived;
        }

        public ChunkTracker getChunkTracker() {
            return chunkTracker;
        }

        public CompletableFuture<Void> getTransferFuture() {
            return transferFuture;
        }

        public boolean isCompleted() {
            return isCompleted;
        }

        public void setCompleted(boolean completed) {
            isCompleted = completed;
            if (completed) {
                transferFuture.complete(null);
            }
        }

        public int getChunkSize() {
            return chunkSize;
        }
    }


    /**
     * Estructura para rastrear chunks enviados
     */
    private static class ChunkTracker {
        private final boolean[] acknowledgedChunks;
        private final int totalChunks;
        private int acknowledgedCount = 0;
        private final long[] sentTimestamps;

        public ChunkTracker(int totalChunks) {
            this.totalChunks = totalChunks;
            this.acknowledgedChunks = new boolean[totalChunks];
            this.sentTimestamps = new long[totalChunks];
        }

        public void markSent(int chunkNumber) {
            sentTimestamps[chunkNumber] = System.currentTimeMillis();
        }

        public void markAcknowledged(int chunkNumber) {
            if (!acknowledgedChunks[chunkNumber]) {
                acknowledgedChunks[chunkNumber] = true;
                acknowledgedCount++;
            }
        }

        public boolean isAcknowledged(int chunkNumber) {
            return acknowledgedChunks[chunkNumber];
        }

        public boolean isComplete() {
            return acknowledgedCount == totalChunks;
        }

        public float getProgress() {
            return (float) acknowledgedCount / totalChunks;
        }

        public int[] getUnacknowledgedChunks() {
            int[] unacknowledged = new int[totalChunks - acknowledgedCount];
            int index = 0;
            for (int i = 0; i < totalChunks; i++) {
                if (!acknowledgedChunks[i]) {
                    unacknowledged[index++] = i;
                }
            }
            return unacknowledged;
        }
    }
}