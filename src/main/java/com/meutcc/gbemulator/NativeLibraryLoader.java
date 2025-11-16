package com.meutcc.gbemulator;

import java.io.*;
import java.nio.file.*;

/**
 * NativeLibraryLoader - Extrai e carrega automaticamente as DLLs nativas do JInput
 * 
 * Esta classe é responsável por:
 * - Detectar o sistema operacional e arquitetura
 * - Extrair as DLLs do JAR para um diretório temporário
 * - Configurar o java.library.path para encontrar as DLLs
 * 
 * As DLLs são embutidas no JAR durante o build e extraídas na primeira execução.
 */
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
    
    /**
     * Carrega as bibliotecas nativas do JInput apropriadas para o SO atual
     */
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
            
            // Cria diretório temporário para as bibliotecas nativas
            Path tempDir = createTempDirectory();
            
            // Extrai cada biblioteca do JAR
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
            
            // Configura o java.library.path
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
    
    /**
     * Retorna as bibliotecas apropriadas para o sistema operacional
     */
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
    
    /**
     * Cria um diretório temporário para as bibliotecas nativas
     */
    private static Path createTempDirectory() throws IOException {
        Path tempDir = Files.createTempDirectory("jinput-natives-");
        
        // Marca para deletar ao sair da JVM
        tempDir.toFile().deleteOnExit();
        
        return tempDir;
    }
    
    /**
     * Extrai uma biblioteca do JAR para o diretório temporário
     */
    private static boolean extractLibrary(String libName, Path targetDir) {
        try {
            // Tenta carregar do classpath (dentro do JAR, na pasta /native/)
            InputStream in = NativeLibraryLoader.class.getResourceAsStream("/native/" + libName);
            
            if (in == null) {
                // Tenta da raiz do classpath
                in = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(libName);
            }
            
            if (in == null) {
                System.err.println("  ✗ Biblioteca não encontrada no JAR: " + libName);
                return false;
            }
            
            // Cria arquivo de destino
            Path targetFile = targetDir.resolve(libName);
            
            // Copia do JAR para o arquivo
            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            in.close();
            
            // Marca arquivo para deletar ao sair
            targetFile.toFile().deleteOnExit();
            
            System.out.println("  ✓ Extraído: " + libName);
            return true;
            
        } catch (IOException e) {
            System.err.println("  ✗ Erro ao extrair " + libName + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Configura o java.library.path para incluir o diretório das bibliotecas
     */
    private static void configureLibraryPath(Path nativeDir) {
        try {
            // Adiciona ao início do java.library.path
            String currentPath = System.getProperty("java.library.path", "");
            String newPath = nativeDir.toString() + File.pathSeparator + currentPath;
            System.setProperty("java.library.path", newPath);
            
            // HACK: Força o ClassLoader a recarregar sys_paths
            // Isso é necessário porque o java.library.path só é lido uma vez na inicialização
            try {
                java.lang.reflect.Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
                fieldSysPath.setAccessible(true);
                fieldSysPath.set(null, null);
            } catch (Exception e) {
                // Falha silenciosa - em algumas versões do Java isso não funciona
                // mas as bibliotecas já foram extraídas e o System.loadLibrary ainda pode funcionar
            }
            
        } catch (Exception e) {
            System.err.println("⚠ Aviso: Não foi possível atualizar java.library.path: " + e.getMessage());
        }
    }
    
    /**
     * Verifica se as bibliotecas já foram carregadas
     */
    public static boolean isLoaded() {
        return loaded;
    }
}
