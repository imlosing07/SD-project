package com.basrikahveci.p2p.peer.network.message;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;

import com.basrikahveci.p2p.peer.Peer;
import com.basrikahveci.p2p.peer.network.Connection;

public class FileMessage extends TextMessage {

    private static final long serialVersionUID = 2L; 
    private static final int maxContent = 5 * 1024 * 1024;
    private final byte[] fileContent;
    private final String fileName;
    private boolean valido = true;

    public boolean isValido() {
        return valido;
    }
    // Este metodo se encarga de verificar si el archivo existe y tiene contenido
    private String getUniqueFileName(String directory, String fileName) {
        File file = Paths.get(directory, fileName).toFile();
        if (!file.exists()) {
            return fileName; 
        }
    
        String name = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
    
        if (dotIndex != -1) {
            name = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }
    
        int count = 1;
        String newFileName;
        do {
            newFileName = name + "(" + count + ")" + extension;
            file = Paths.get(directory, newFileName).toFile();
            count++;
        } while (file.exists());
    
        return newFileName;
    }

    public void saveFile(String directory) {
        // Create directory if it doesn't exist
        File dir = new File(directory);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Extract just the filename part if it's an absolute path
        String fileNameOnly = new File(fileName).getName();

        String uniqueFileName = getUniqueFileName(directory, fileNameOnly);
        File file = Paths.get(directory, uniqueFileName).toFile();

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(fileContent);
            fos.flush();
            System.out.println("> Archivo guardado: " + file.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("> Error al guardar el archivo: " + e.getMessage());
        }
    }

    public FileMessage(String content, String nombre, String fileName) {
        super(content, nombre, "file");
        this.fileName = fileName;
        // procesar file
        File file = new File(fileName);
        // Verificamos si el archivo existe y tiene contenido
         if (!file.exists() || file.length() == 0 || file.length() > maxContent) {
            valido = false;
            this.fileContent = null;
            return;
        }
        byte[] fileContentTemp = new byte[(int) file.length()];
         // Intentamos leer el archivo
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(fileContentTemp); 
        } catch (IOException e) {
            valido = false;
            this.fileContent = null;
            return;
        }
        this.fileContent = fileContentTemp;
    }
    
    @Override
    public void handle(Peer peer, Connection connection) {
        peer.handlesendfile(connection, this);
    }

    @Override
    public String toString() {
        return "File{" +
            "filename=" + fileName +
            '}';
    }
}
