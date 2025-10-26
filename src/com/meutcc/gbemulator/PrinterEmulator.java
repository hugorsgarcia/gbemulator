package com.meutcc.gbemulator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PrinterEmulator implements Serial.SerialDevice {
    
    private enum State {
        WAITING_MAGIC_1,    
        WAITING_MAGIC_2,    
        READING_COMMAND,    
        READING_COMPRESSION,
        READING_LENGTH_LOW, 
        READING_LENGTH_HIGH,
        READING_DATA,      
        READING_CHECKSUM_LOW, 
        READING_CHECKSUM_HIGH,
        READING_ALIVE,
        READING_STATUS,
        SENDING_RESPONSE
    }
    
    private State state = State.WAITING_MAGIC_1;
    private int command;
    private boolean compressed;
    private int dataLength;
    private int bytesRead;
    private final ByteArrayOutputStream dataBuffer = new ByteArrayOutputStream();
    private int checksumExpected;
    private int checksumCalculated;
    
    private final List<byte[]> imageTiles = new ArrayList<>();
    private int printedImages = 0;
    
    private String outputDirectory = "printer_output";
    
    private static final int[] PALETTE = {
        0xFFFFFFFF, // Branco
        0xFFA0A0A0, // Cinza claro
        0xFF505050, // Cinza escuro
        0xFF000000  // Preto
    };
    
    private static final int TILE_WIDTH = 8;
    private static final int TILE_HEIGHT = 8;
    private static final int TILES_PER_ROW = 20;
    private static final int IMAGE_WIDTH = 160;
    
    private int printerStatus = 0x00; 
    
    public PrinterEmulator() {
        try {
            Files.createDirectories(Paths.get(outputDirectory));
            System.out.println("Printer: Diretório de saída criado: " + outputDirectory);
        } catch (IOException e) {
            System.err.println("Printer: Erro ao criar diretório - " + e.getMessage());
        }
    }
    

    public void setOutputDirectory(String directory) {
        this.outputDirectory = directory;
        try {
            Files.createDirectories(Paths.get(directory));
        } catch (IOException e) {
            System.err.println("Printer: Erro ao criar diretório - " + e.getMessage());
        }
    }
    
    @Override
    public int exchangeByte(int dataSent) {
        int response = processInputByte(dataSent & 0xFF);
        return response & 0xFF;
    }
    
    private int processInputByte(int data) {
        switch (state) {
            case WAITING_MAGIC_1:
                if (data == 0x88) {
                    state = State.WAITING_MAGIC_2;
                    return 0x00; 
                }
                return 0x00;
                
            case WAITING_MAGIC_2:
                if (data == 0x33) {
                    state = State.READING_COMMAND;
                    checksumCalculated = 0;
                    return 0x00; 
                } else {
                    state = State.WAITING_MAGIC_1;
                }
                return 0x00;
                
            case READING_COMMAND:
                command = data;
                checksumCalculated += data;
                state = State.READING_COMPRESSION;
                return 0x00;
                
            case READING_COMPRESSION:
                compressed = (data != 0);
                checksumCalculated += data;
                state = State.READING_LENGTH_LOW;
                return 0x00;
                
            case READING_LENGTH_LOW:
                dataLength = data;
                checksumCalculated += data;
                state = State.READING_LENGTH_HIGH;
                return 0x00;
                
            case READING_LENGTH_HIGH:
                dataLength |= (data << 8);
                checksumCalculated += data;
                
                dataBuffer.reset();
                bytesRead = 0;
                
                if (dataLength > 0) {
                    state = State.READING_DATA;
                } else {
                    state = State.READING_CHECKSUM_LOW;
                }
                return 0x00;
                
            case READING_DATA:
                dataBuffer.write(data);
                checksumCalculated += data;
                bytesRead++;
                
                if (bytesRead >= dataLength) {
                    state = State.READING_CHECKSUM_LOW;
                }
                return 0x00;
                
            case READING_CHECKSUM_LOW:
                checksumExpected = data;
                state = State.READING_CHECKSUM_HIGH;
                return 0x00;
                
            case READING_CHECKSUM_HIGH:
                checksumExpected |= (data << 8);
                state = State.READING_ALIVE;
                return 0x00;
                
            case READING_ALIVE:
                state = State.READING_STATUS;
                return 0x00;
                
            case READING_STATUS:
                state = State.SENDING_RESPONSE;
                
                processCommand();
                
                return 0x81;
                
            case SENDING_RESPONSE:
                state = State.WAITING_MAGIC_1;
                return printerStatus;
                
            default:
                state = State.WAITING_MAGIC_1;
                return 0x00;
        }
    }
    

    private void processCommand() {
        checksumCalculated &= 0xFFFF;
        if (checksumCalculated != checksumExpected) {
            System.err.println(String.format("Printer: Erro de checksum! Esperado=0x%04X, Calculado=0x%04X",
                checksumExpected, checksumCalculated));
            printerStatus = 0x01; 
            return;
        }
        
        printerStatus = 0x00; 
        
        switch (command) {
            case 0x01: 
                System.out.println("Printer: Comando Initialize");
                imageTiles.clear();
                break;
                
            case 0x02: 
                System.out.println("Printer: Comando Print");
                printImage();
                break;
                
            case 0x04: 
                System.out.println("Printer: Comando Data (" + dataLength + " bytes)");
                addImageData(dataBuffer.toByteArray());
                break;
                
            case 0x0F: 
                System.out.println("Printer: Comando Status");
                break;
                
            default:
                System.err.println("Printer: Comando desconhecido: 0x" + Integer.toHexString(command));
        }
    }

    private void addImageData(byte[] data) {
        
        int offset = 0;
        while (offset + 16 <= data.length) {
            byte[] tile = new byte[16];
            System.arraycopy(data, offset, tile, 0, 16);
            imageTiles.add(tile);
            offset += 16;
        }
        
        System.out.println("Printer: Adicionados " + (data.length / 16) + " tiles (total: " + imageTiles.size() + ")");
    }

    private void printImage() {
        if (imageTiles.isEmpty()) {
            System.out.println("Printer: Nenhum dado para imprimir");
            return;
        }
        
        try {
            int totalTiles = imageTiles.size();
            int rows = (totalTiles + TILES_PER_ROW - 1) / TILES_PER_ROW;
            int imageHeight = rows * TILE_HEIGHT;
            
            System.out.println(String.format("Printer: Gerando imagem %dx%d (%d tiles)", 
                IMAGE_WIDTH, imageHeight, totalTiles));
            
            BufferedImage image = new BufferedImage(IMAGE_WIDTH, imageHeight, BufferedImage.TYPE_INT_ARGB);
            
            for (int tileIndex = 0; tileIndex < totalTiles; tileIndex++) {
                int tileX = (tileIndex % TILES_PER_ROW) * TILE_WIDTH;
                int tileY = (tileIndex / TILES_PER_ROW) * TILE_HEIGHT;
                
                renderTile(image, imageTiles.get(tileIndex), tileX, tileY);
            }
            
            String filename = String.format("print_%03d_%s.png", 
                ++printedImages, 
                new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()));
            
            Path outputPath = Paths.get(outputDirectory, filename);
            ImageIO.write(image, "PNG", outputPath.toFile());
            
            System.out.println("Printer: Imagem salva: " + outputPath.toAbsolutePath());
            
            imageTiles.clear();
            
        } catch (IOException e) {
            System.err.println("Printer: Erro ao salvar imagem - " + e.getMessage());
            printerStatus = 0x02; // Erro ao imprimir
        }
    }
    
    private void renderTile(BufferedImage image, byte[] tileData, int x, int y) {
        
        for (int line = 0; line < 8; line++) {
            int byte1 = tileData[line * 2] & 0xFF;
            int byte2 = tileData[line * 2 + 1] & 0xFF;
            
            for (int pixel = 0; pixel < 8; pixel++) {
                int bit = 7 - pixel;
                int colorLow = (byte1 >> bit) & 1;
                int colorHigh = (byte2 >> bit) & 1;
                int colorIndex = (colorHigh << 1) | colorLow;
                
                int pixelX = x + pixel;
                int pixelY = y + line;
                
                if (pixelX < image.getWidth() && pixelY < image.getHeight()) {
                    image.setRGB(pixelX, pixelY, PALETTE[colorIndex]);
                }
            }
        }
    }
    

    public int getPrintedImagesCount() {
        return printedImages;
    }
    

    public void reset() {
        state = State.WAITING_MAGIC_1;
        imageTiles.clear();
        dataBuffer.reset();
        printerStatus = 0x00;
        System.out.println("Printer: Reset");
    }
}
