package com.meutcc.gbemulator;

import java.io.*;
import java.nio.file.*;

public class NativeLibraryLoader {
    
    private static final String[] WINDOWS_64_LIBS = {
        "jinput-dx8_64.dll",
        "jinput-raw_64.dll",
        "jinput-wintab.dll"
    };
    
    private static final String[] LINUX_64_LIBS = {
        "libjinput-linux64.so"
    };
    
    private static final String[] MAC_LIBS = {
        "libjinput-osx.jnilib"
    };
    
    private static boolean loaded = false;

    public static void loadJInputLibraries() {
        if (loaded) {
            System.out.println("✓ Bibliotecas nativas do JInput já foram carregadas");
            return;
        }
        
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        
        System.out.println("=== Carregando Bibliotecas Nativas do JInput ===");
        System.out.println("Sistema Operacional: " + osName);
        System.out.println("Arquitetura: " + osArch);
        
        try {
            String[] libraries = getLibrariesForPlatform(osName, osArch);
            
            if (libraries == null || libraries.length == 0) {
                System.out.println("⚠ Sistema não suportado para gamepad: " + osName + " (" + osArch + ")");
                System.out.println("  Suporte a gamepad disponível apenas para:");
                System.out.println("  - Windows 64-bit");
                System.out.println("  - Linux 64-bit");
                System.out.println("  - macOS");
                return;
            }
            
            Path tempDir = createTempDirectory();
            
            int extractedCount = 0;
            for (String libName : libraries) {
                if (extractLibrary(libName, tempDir)) {
                    extractedCount++;
                }
            }
            
            if (extractedCount == 0) {
                System.err.println("⚠ Nenhuma biblioteca nativa foi extraída!");
                System.err.println("  As DLLs podem não estar incluídas no JAR.");
                System.err.println("  Execute: gradlew clean jar");
                return;
            }
            
            configureLibraryPath(tempDir);
            
            loaded = true;
            System.out.println("✓ " + extractedCount + " biblioteca(s) nativa(s) carregada(s) com sucesso!");
            System.out.println("✓ Diretório: " + tempDir);
            System.out.println("==============================================\n");
            
        } catch (Exception e) {
            System.err.println("✗ Erro ao carregar bibliotecas nativas: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String[] getLibrariesForPlatform(String osName, String osArch) {
        if (osName.contains("win")) {
            if (osArch.contains("64") || osArch.contains("amd64")) {
                return WINDOWS_64_LIBS;
            } else {
                System.err.println("⚠ Windows 32-bit não é suportado. Use Windows 64-bit.");
                return null;
            }
        } else if (osName.contains("linux")) {
            if (osArch.contains("64") || osArch.contains("amd64")) {
                return LINUX_64_LIBS;
            } else {
                System.err.println("⚠ Linux 32-bit não é suportado. Use Linux 64-bit.");
                return null;
            }
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return MAC_LIBS;
        }
        
        return null;
    }

    private static Path createTempDirectory() throws IOException {
        Path tempDir = Files.createTempDirectory("jinput-natives-");
        
        tempDir.toFile().deleteOnExit();
        
        return tempDir;
    }

    private static boolean extractLibrary(String libName, Path targetDir) {
        try {
            InputStream in = NativeLibraryLoader.class.getResourceAsStream("/native/" + libName);
            
            if (in == null) {
                in = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(libName);
            }
            
            if (in == null) {
                System.err.println("  ✗ Biblioteca não encontrada no JAR: " + libName);
                return false;
            }
            
            Path targetFile = targetDir.resolve(libName);
            
            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            in.close();
            
            targetFile.toFile().deleteOnExit();
            
            System.out.println("  ✓ Extraído: " + libName);
            return true;
            
        } catch (IOException e) {
            System.err.println("  ✗ Erro ao extrair " + libName + ": " + e.getMessage());
            return false;
        }
    }
    
    private static void configureLibraryPath(Path nativeDir) {
        try {
            String currentPath = System.getProperty("java.library.path", "");
            String newPath = nativeDir.toString() + File.pathSeparator + currentPath;
            System.setProperty("java.library.path", newPath);
            
            try {
                java.lang.reflect.Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
                fieldSysPath.setAccessible(true);
                fieldSysPath.set(null, null);
            } catch (Exception e) {
            }
            
        } catch (Exception e) {
            System.err.println("⚠ Aviso: Não foi possível atualizar java.library.path: " + e.getMessage());
        }
    }
    
    public static boolean isLoaded() {
        return loaded;
    }
}
