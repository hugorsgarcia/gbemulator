# Game Boy Emulator (Projeto de TCC)

Este projeto é um emulador do Game Boy clássico (DMG) desenvolvido em Java como Trabalho de Conclusão de Curso (TCC). O objetivo é simular o funcionamento do hardware original do Game Boy, permitindo a execução de jogos clássicos em um ambiente moderno.

## Sumário

- [Descrição Geral](#descrição-geral)
- [Arquitetura do Hardware do Game Boy](#arquitetura-do-hardware-do-game-boy)
- [Ciclo de Emulação](#ciclo-de-emulação)
- [Controles](#controles)
- [Como Usar](#como-usar)
- [Licença](#licença)

---

## Descrição Geral

O emulador implementa os principais componentes do Game Boy:

- **CPU**: Processador Sharp LR35902 (baseado no Z80, 8 bits)
- **PPU**: Unidade de processamento gráfico (Pixel Processing Unit)
- **APU**: Unidade de processamento de áudio (Audio Processing Unit)
- **MMU**: Unidade de gerenciamento de memória (Memory Management Unit)
- **Cartridge**: Suporte a ROMs e RAM externa (incluindo MBC1 básico)
- **InputHandler**: Mapeamento de teclado para os botões do Game Boy
- **Janela gráfica**: Exibição da tela e captura de entrada do usuário

---

## Arquitetura do Hardware do Game Boy

- **CPU**: 8 bits, clock de ~4.19 MHz, registradores A, F, B, C, D, E, H, L, SP, PC.
- **Memória**:
  - 8KB VRAM (0x8000-0x9FFF)
  - 8KB RAM externa (cartucho, 0xA000-0xBFFF)
  - 8KB WRAM (0xC000-0xDFFF)
  - Echo RAM (0xE000-0xFDFF)
  - OAM (Sprite Attribute Table, 0xFE00-0xFE9F)
  - I/O Registers (0xFF00-0xFF7F)
  - HRAM (0xFF80-0xFFFE)
  - Interrupt Enable (0xFFFF)
- **PPU**: Resolução 160x144 pixels, 4 tons de cinza, 40 sprites, 2 planos (BG/Window e Sprites).
- **APU**: 4 canais de som (2 Pulse, 1 Wave, 1 Noise), controle de volume, panning, envelopes.
- **Joypad**: 8 botões (direcional, A, B, Start, Select).

---

## Ciclo de Emulação

O ciclo principal do emulador segue o fluxo do hardware real:

1. **CPU executa instrução**: O método `cpu.step()` busca, decodifica e executa uma instrução, retornando o número de ciclos consumidos.
2. **PPU atualiza vídeo**: O método `ppu.update(cycles)` avança o estado da PPU, renderizando linhas e frames conforme os ciclos da CPU.
3. **APU atualiza áudio**: O método `apu.update(cycles)` avança o sequenciador de áudio, gera amostras e controla envelopes/sweep/length.
4. **MMU gerencia memória**: Todas as leituras/escritas passam pela MMU, que direciona para RAM, VRAM, OAM, registradores de I/O, etc.
5. **InputHandler lê teclado**: Eventos de teclado são convertidos em estados dos botões do Game Boy.
6. **Janela gráfica exibe frame**: O buffer de vídeo é desenhado na tela a cada frame completo.

O loop principal sincroniza a execução para manter 60 frames por segundo, respeitando o timing do hardware original.

---

## Controles

| Game Boy | Teclado PC |
|----------|------------|
| Direita  | Seta Direita |
| Esquerda | Seta Esquerda |
| Cima     | Seta Cima |
| Baixo    | Seta Baixo |
| A        | Z |
| B        | X |
| Start    | Enter |
| Select   | Shift |

---

## Como Usar

1. Compile o projeto Java.
2. Execute a classe principal (`Main.java` ou `GameBoyWindow.java`).
3. Use o menu para carregar uma ROM de Game Boy (`.gb`).
4. Jogue usando o teclado conforme o mapeamento acima.

---

## Licença

Este projeto é acadêmico e não deve ser usado para fins comerciais. ROMs de jogos não são distribuídas.

---
