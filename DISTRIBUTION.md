# Guia de Distribuição do Emulador Game Boy

## 📦 Gerando a Build Final

Para gerar o JAR executável com todas as dependências embutidas:

```bash
./gradlew build
```

O arquivo final será gerado em: `build/libs/gbemulator-2.0.jar`

## 🔧 Bibliotecas Nativas Embutidas

O JAR já inclui todas as DLLs necessárias do JInput para Windows 64-bit:

- `jinput-dx8_64.dll` - DirectInput 8 support
- `jinput-raw_64.dll` - Raw Input support  
- `jinput-wintab.dll` - Tablet/Stylus support

**Localização no JAR:** `/native/`

### Como Funciona

1. As DLLs estão armazenadas em `src/main/resources/native/`
2. Durante a build, são automaticamente incluídas no JAR
3. Na inicialização, `NativeLibraryLoader.loadJInputLibraries()` extrai as DLLs para um diretório temporário
4. O `java.library.path` é atualizado dinamicamente para usar as DLLs extraídas

## 📋 Requisitos do Usuário Final

- **Java 21** ou superior
- **Windows 64-bit** (outras plataformas não suportadas atualmente)
- **ROM do Game Boy** (.gb ou .gbc)

## 🚀 Instruções para o Usuário Final

### Instalação

1. Baixar o arquivo `gbemulator-2.0.jar`
2. Garantir que o Java 21+ está instalado
3. Executar o JAR

### Execução

**Modo Gráfico (duplo clique):**
```
Duplo clique em gbemulator-2.0.jar
```

**Via terminal:**
```bash
java -jar gbemulator-2.0.jar
```

### Carregar ROM

1. Menu: `File → Load ROM`
2. Selecionar arquivo `.gb` ou `.gbc`

## 🎮 Suporte a Controles

### Controles Padrão (Teclado)

- **Direcionais:** Setas do teclado
- **A:** Z
- **B:** X
- **Start:** Enter
- **Select:** Shift

### Gamepads/Controllers

O emulador suporta gamepads via JInput (DirectInput). As DLLs necessárias já estão embutidas no JAR.

#### ⚠️ Limitações Conhecidas

**Switch Pro Controller via Bluetooth no Windows 11:**
- O JInput tem limitações com o DirectInput em controladores Bluetooth modernos no Windows 11
- O controller é detectado, mas pode falhar no polling de inputs

**Soluções alternativas:**

1. **Usar cabo USB** (funciona imediatamente)
2. **Steam Input:** Abrir o emulador através do Steam com Steam Input habilitado
3. **BetterJoy:** Usar o [BetterJoy](https://github.com/Davidobot/BetterJoy) para converter para XInput

### Configurar Gamepad

1. Menu: `Options → Configure Gamepad`
2. Conectar o gamepad
3. Clicar em "Detect Gamepad"
4. Mapear os botões conforme desejado
5. Salvar configuração

## 🎨 Recursos de Vídeo

### Paletas de Cores

Menu: `Video → Palette`

- **DMG Classic** - Verde clássico do Game Boy original
- **Grayscale** - Tons de cinza puros
- **Soft Grayscale** - Cinza suave
- **Green Vibrant** - Verde vibrante
- **Amber** - Tom âmbar (CRT)
- **Blue** - Tom azulado
- **Sepia** - Tom sépia vintage

### Filtros de Escala

Menu: `Video → Scaling Filter`

- **Nearest Neighbor** - Pixelado nítido (padrão retro)
- **Bilinear** - Suavização leve
- **Bicubic** - Suavização avançada

### Efeitos de Tela

Menu: `Video → Screen Effects`

- **LCD Ghosting** - Efeito de desfoque de movimento do LCD original
- **Grid Lines** - Linhas de grade simulando pixels LCD
- **Scanlines** - Linhas horizontais simulando CRT

## 📂 Estrutura de Distribuição

```
gbemulator-2.0.jar
│
├─ META-INF/
│  └─ MANIFEST.MF (Main-Class: com.meutcc.gbemulator.Main)
│
├─ com/meutcc/gbemulator/
│  ├─ Main.class
│  ├─ GameBoyWindow.class
│  ├─ NativeLibraryLoader.class
│  └─ ... (outras classes)
│
├─ native/
│  ├─ jinput-dx8_64.dll
│  ├─ jinput-raw_64.dll
│  └─ jinput-wintab.dll
│
└─ net/java/games/input/
   └─ ... (classes do JInput)
```

## 🐛 Solução de Problemas

### "Não foi possível carregar as bibliotecas nativas do JInput"

**Causa:** Falha ao extrair DLLs do JAR

**Solução:**
1. Verificar se você tem permissões de escrita no diretório temporário do sistema
2. Verificar se o antivírus não está bloqueando a extração de DLLs
3. Executar como administrador (se necessário)

### Gamepad não detectado

**Causa:** DLLs do JInput não carregadas ou controller incompatível

**Solução:**
1. Verificar se as DLLs foram extraídas corretamente (mensagens no console)
2. Tentar reconectar o gamepad
3. Usar cabo USB em vez de Bluetooth (para Switch Pro Controller)
4. Verificar drivers do Windows atualizados

### Performance baixa

**Solução:**
1. Desabilitar filtros de escala avançados (usar Nearest Neighbor)
2. Desabilitar efeitos de tela (ghosting, grid, scanlines)
3. Verificar se o Java está usando aceleração de hardware

## 📝 Notas de Desenvolvimento

### Adicionar suporte para outras plataformas

Para suportar Linux ou macOS, você precisaria:

1. Adicionar as DLLs/SO/DYLIB correspondentes em `src/main/resources/native/`
2. Atualizar `NativeLibraryLoader.java` para detectar e carregar bibliotecas específicas da plataforma
3. Testar em cada plataforma

### Atualizar versão do JInput

1. Atualizar dependência em `build.gradle`
2. Atualizar DLLs em `src/main/resources/native/` (baixar do repositório do JInput)
3. Testar compatibilidade

## 📄 Licença

Este projeto inclui:
- **JInput 2.0.9** (BSD License)
- Código do emulador (sua licença aqui)

## 🔗 Links Úteis

- [JInput GitHub](https://github.com/jinput/jinput)
- [BetterJoy para Switch Pro Controller](https://github.com/Davidobot/BetterJoy)
- [OpenJDK Downloads](https://adoptium.net/)
