package com.meutcc.gbemulator;

import java.util.Arrays;

// Representa a CPU Sharp LR35902
public class CPU {

    private final MMU mmu;

    // Registradores de 8 bits
    private int a, f, b, c, d, e, h, l;
    // Registradores de 16 bits
    private int sp; // Stack Pointer
    private int pc; // Program Counter

    // Flags (no registrador F)
    // Bit 7: Zero (Z)
    // Bit 6: Subtraction (N)
    // Bit 5: Half Carry (H)
    // Bit 4: Carry (C)
    // Bits 3-0: Não usados (sempre 0)
    private static final int ZERO_FLAG = 0x80;
    private static final int SUBTRACT_FLAG = 0x40;
    private static final int HALF_CARRY_FLAG = 0x20;
    private static final int CARRY_FLAG = 0x10;

    private boolean ime; // Interrupt Master Enable
    private boolean halted;
    private int cycles = 0; // Ciclos da instrução atual

    public CPU(MMU mmu) {
        this.mmu = mmu;
        reset();
    }

    public void reset() {
        a = 0x01; f = 0xB0; // Padrão para DMG Pós-BootROM.
        b = 0x00; c = 0x13;
        d = 0x00; e = 0xD8;
        h = 0x01; l = 0x4D;
        sp = 0xFFFE;
        pc = 0x0100; // Ponto de entrada da ROM do cartucho
        ime = false;
        halted = false;
        cycles = 0;
        System.out.println("CPU reset. PC=" + String.format("0x%04X", pc));
    }

    public int step() {
        cycles = 0;
        if (halted) {
            cycles = 4;
            // handleInterrupts() cuidará de tirar do HALT se uma interrupção ocorrer
            return cycles;
        }

        int currentPc = pc; // Para depuração
        int opcode = fetch();
        // Descomente para depuração pesada de opcodes:
        // System.out.println(String.format("PC:0x%04X Op:0x%02X AF:%02X%02X BC:%02X%02X DE:%02X%02X HL:%02X%02X SP:0x%04X IME:%b Z:%d N:%d H:%d C:%d IF:0x%02X IE:0x%02X LCDC:0x%02X LY:0x%02X STAT:0x%02X",
        //         currentPc, opcode, a,f, b,c, d,e, h,l, sp, ime,
        //         getZeroFlag()?1:0, getSubtractFlag()?1:0, getHalfCarryFlag()?1:0, getCarryFlag()?1:0,
        //         mmu.readByte(MMU.REG_IF), mmu.readByte(MMU.REG_IE),
        //         mmu.readByte(MMU.REG_LCDC), mmu.readByte(MMU.REG_LY), mmu.readByte(MMU.REG_STAT) ));

        decodeAndExecute(opcode);

        return cycles;
    }

    private int fetch() {
        int opcode = mmu.readByte(pc);
        pc = (pc + 1) & 0xFFFF;
        return opcode;
    }

    private int fetchWord() {
        int lsb = fetch();
        int msb = fetch();
        return (msb << 8) | lsb;
    }

    private void decodeAndExecute(int opcode) {
        switch (opcode) {
            // NOP
            case 0x00: cycles = 4; break;

            // LD BC,d16
            case 0x01: setBC(fetchWord()); cycles = 12; break;
            // LD (BC),A
            case 0x02: mmu.writeByte(getBC(), a); cycles = 8; break;
            // INC BC
            case 0x03: setBC(getBC() + 1); cycles = 8; break;
            // INC B
            case 0x04: b = inc8(b); cycles = 4; break;
            // DEC B
            case 0x05: b = dec8(b); cycles = 4; break;
            // LD B,d8
            case 0x06: b = fetch(); cycles = 8; break;
            // RLCA
            case 0x07: rlca(); cycles = 4; break;
            // LD (a16),SP
            case 0x08:
                int addr = fetchWord();
                mmu.writeByte(addr, sp & 0xFF);
                mmu.writeByte((addr + 1) & 0xFFFF, (sp >> 8) & 0xFF);
                cycles = 20;
                break;

            // ADD HL,BC
            case 0x09: addHL(getBC()); cycles = 8; break;
            // LD A,(BC)
            case 0x0A: a = mmu.readByte(getBC()); cycles = 8; break;
            // DEC BC
            case 0x0B: setBC(getBC() - 1); cycles = 8; break;
            // INC C
            case 0x0C: c = inc8(c); cycles = 4; break;
            // DEC C
            case 0x0D: c = dec8(c); cycles = 4; break;
            // LD C,d8
            case 0x0E: c = fetch(); cycles = 8; break;
            // RRCA
            case 0x0F: rrca(); cycles = 4; break;

            // STOP (0x10 0x00)
            case 0x10:
                fetch(); // Consome o byte 0x00 que acompanha STOP
                cycles = 4;
                break;


            // LD DE,d16
            case 0x11: setDE(fetchWord()); cycles = 12; break;
            // LD (DE),A
            case 0x12: mmu.writeByte(getDE(), a); cycles = 8; break;
            // INC DE
            case 0x13: setDE(getDE() + 1); cycles = 8; break;
            // INC D
            case 0x14: d = inc8(d); cycles = 4; break;
            // DEC D
            case 0x15: d = dec8(d); cycles = 4; break;
            // LD D,d8
            case 0x16: d = fetch(); cycles = 8; break;
            // RLA
            case 0x17: rla(); cycles = 4; break;
            // JR r8
            case 0x18: jr_unconditional(); cycles = 12; break;

            // ADD HL,DE
            case 0x19: addHL(getDE()); cycles = 8; break;
            // LD A,(DE)
            case 0x1A: a = mmu.readByte(getDE()); cycles = 8; break;
            // DEC DE
            case 0x1B: setDE(getDE() - 1); cycles = 8; break;
            // INC E
            case 0x1C: e = inc8(e); cycles = 4; break;
            // DEC E
            case 0x1D: e = dec8(e); cycles = 4; break;
            // LD E,d8
            case 0x1E: e = fetch(); cycles = 8; break;
            // RRA
            case 0x1F: rra(); cycles = 4; break;

            // JR NZ,r8
            case 0x20: if (!getZeroFlag()) { jr_unconditional(); cycles = 12; } else { fetch(); cycles = 8; } break;
            // LD HL,d16
            case 0x21: setHL(fetchWord()); cycles = 12; break;
            // LD (HL+),A ou LDI (HL),A
            case 0x22: mmu.writeByte(getHL(), a); setHL(getHL() + 1); cycles = 8; break;
            // INC HL
            case 0x23: setHL(getHL() + 1); cycles = 8; break;
            // INC H
            case 0x24: h = inc8(h); cycles = 4; break;
            // DEC H
            case 0x25: h = dec8(h); cycles = 4; break;
            // LD H,d8
            case 0x26: h = fetch(); cycles = 8; break;
            // DAA
            case 0x27: daa(); cycles = 4; break;
            // JR Z,r8
            case 0x28: if (getZeroFlag()) { jr_unconditional(); cycles = 12; } else { fetch(); cycles = 8; } break;
            // ADD HL,HL
            case 0x29: addHL(getHL()); cycles = 8; break;
            // LD A,(HL+) ou LDI A,(HL)
            case 0x2A: a = mmu.readByte(getHL()); setHL(getHL() + 1); cycles = 8; break;
            // DEC HL
            case 0x2B: setHL(getHL() - 1); cycles = 8; break;
            // INC L
            case 0x2C: l = inc8(l); cycles = 4; break;
            // DEC L
            case 0x2D: l = dec8(l); cycles = 4; break;
            // LD L,d8
            case 0x2E: l = fetch(); cycles = 8; break;
            // CPL
            case 0x2F: cpl(); cycles = 4; break;

            // JR NC,r8
            case 0x30: if (!getCarryFlag()) { jr_unconditional(); cycles = 12; } else { fetch(); cycles = 8; } break;
            // LD SP,d16
            case 0x31: sp = fetchWord(); cycles = 12; break;
            // LD (HL-),A ou LDD (HL),A
            case 0x32: mmu.writeByte(getHL(), a); setHL(getHL() - 1); cycles = 8; break;
            // INC SP
            case 0x33: sp = (sp + 1) & 0xFFFF; cycles = 8; break;
            // INC (HL)
            case 0x34: int valHL = mmu.readByte(getHL()); mmu.writeByte(getHL(), inc8(valHL)); cycles = 12; break;
            // DEC (HL)
            case 0x35: valHL = mmu.readByte(getHL()); mmu.writeByte(getHL(), dec8(valHL)); cycles = 12; break;
            // LD (HL),d8
            case 0x36: mmu.writeByte(getHL(), fetch()); cycles = 12; break;
            // SCF
            case 0x37: scf(); cycles = 4; break;
            // JR C,r8
            case 0x38: if (getCarryFlag()) { jr_unconditional(); cycles = 12; } else { fetch(); cycles = 8; } break;
            // ADD HL,SP
            case 0x39: addHL(sp); cycles = 8; break;
            // LD A,(HL-) ou LDD A,(HL)
            case 0x3A: a = mmu.readByte(getHL()); setHL(getHL() - 1); cycles = 8; break;
            // DEC SP
            case 0x3B: sp = (sp - 1) & 0xFFFF; cycles = 8; break;
            // INC A
            case 0x3C: a = inc8(a); cycles = 4; break;
            // DEC A
            case 0x3D: a = dec8(a); cycles = 4; break;
            // LD A,d8
            case 0x3E: a = fetch(); cycles = 8; break;
            // CCF
            case 0x3F: ccf(); cycles = 4; break;

            case 0x40: /* LD B,B */ cycles = 4; break;
            case 0x41: b = c; cycles = 4; break;
            case 0x42: b = d; cycles = 4; break;
            case 0x43: b = e; cycles = 4; break;
            case 0x44: b = h; cycles = 4; break;
            case 0x45: b = l; cycles = 4; break;
            case 0x46: b = mmu.readByte(getHL()); cycles = 8; break;
            case 0x47: b = a; cycles = 4; break;

            case 0x48: c = b; cycles = 4; break;
            case 0x49: /* LD C,C */ cycles = 4; break;
            case 0x4A: c = d; cycles = 4; break;
            case 0x4B: c = e; cycles = 4; break;
            case 0x4C: c = h; cycles = 4; break;
            case 0x4D: c = l; cycles = 4; break;
            case 0x4E: c = mmu.readByte(getHL()); cycles = 8; break;
            case 0x4F: c = a; cycles = 4; break;

            case 0x50: d = b; cycles = 4; break;
            case 0x51: d = c; cycles = 4; break;
            case 0x52: /* LD D,D */ cycles = 4; break;
            case 0x53: d = e; cycles = 4; break;
            case 0x54: d = h; cycles = 4; break;
            case 0x55: d = l; cycles = 4; break;
            case 0x56: d = mmu.readByte(getHL()); cycles = 8; break;
            case 0x57: d = a; cycles = 4; break;

            case 0x58: e = b; cycles = 4; break;
            case 0x59: e = c; cycles = 4; break;
            case 0x5A: e = d; cycles = 4; break;
            case 0x5B: /* LD E,E */ cycles = 4; break;
            case 0x5C: e = h; cycles = 4; break;
            case 0x5D: e = l; cycles = 4; break;
            case 0x5E: e = mmu.readByte(getHL()); cycles = 8; break;
            case 0x5F: e = a; cycles = 4; break;

            case 0x60: h = b; cycles = 4; break;
            case 0x61: h = c; cycles = 4; break;
            case 0x62: h = d; cycles = 4; break;
            case 0x63: h = e; cycles = 4; break;
            case 0x64: /* LD H,H */ cycles = 4; break;
            case 0x65: h = l; cycles = 4; break;
            case 0x66: h = mmu.readByte(getHL()); cycles = 8; break;
            case 0x67: h = a; cycles = 4; break;

            case 0x68: l = b; cycles = 4; break;
            case 0x69: l = c; cycles = 4; break;
            case 0x6A: l = d; cycles = 4; break;
            case 0x6B: l = e; cycles = 4; break;
            case 0x6C: l = h; cycles = 4; break;
            case 0x6D: /* LD L,L */ cycles = 4; break;
            case 0x6E: l = mmu.readByte(getHL()); cycles = 8; break;
            case 0x6F: l = a; cycles = 4; break;

            case 0x70: mmu.writeByte(getHL(), b); cycles = 8; break;
            case 0x71: mmu.writeByte(getHL(), c); cycles = 8; break;
            case 0x72: mmu.writeByte(getHL(), d); cycles = 8; break;
            case 0x73: mmu.writeByte(getHL(), e); cycles = 8; break;
            case 0x74: mmu.writeByte(getHL(), h); cycles = 8; break;
            case 0x75: mmu.writeByte(getHL(), l); cycles = 8; break;
            case 0x76: halted = true; cycles = 4; break;
            case 0x77: mmu.writeByte(getHL(), a); cycles = 8; break;

            case 0x78: a = b; cycles = 4; break;
            case 0x79: a = c; cycles = 4; break;
            case 0x7A: a = d; cycles = 4; break;
            case 0x7B: a = e; cycles = 4; break;
            case 0x7C: a = h; cycles = 4; break;
            case 0x7D: a = l; cycles = 4; break;
            case 0x7E: a = mmu.readByte(getHL()); cycles = 8; break;
            case 0x7F: /* LD A,A */ cycles = 4; break;

            case 0x80: addA(b); cycles = 4; break;
            case 0x81: addA(c); cycles = 4; break;
            case 0x82: addA(d); cycles = 4; break;
            case 0x83: addA(e); cycles = 4; break;
            case 0x84: addA(h); cycles = 4; break;
            case 0x85: addA(l); cycles = 4; break;
            case 0x86: addA(mmu.readByte(getHL())); cycles = 8; break;
            case 0x87: addA(a); cycles = 4; break;

            case 0x88: adcA(b); cycles = 4; break;
            case 0x89: adcA(c); cycles = 4; break;
            case 0x8A: adcA(d); cycles = 4; break;
            case 0x8B: adcA(e); cycles = 4; break;
            case 0x8C: adcA(h); cycles = 4; break;
            case 0x8D: adcA(l); cycles = 4; break;
            case 0x8E: adcA(mmu.readByte(getHL())); cycles = 8; break;
            case 0x8F: adcA(a); cycles = 4; break;

            case 0x90: subA(b); cycles = 4; break;
            case 0x91: subA(c); cycles = 4; break;
            case 0x92: subA(d); cycles = 4; break;
            case 0x93: subA(e); cycles = 4; break;
            case 0x94: subA(h); cycles = 4; break;
            case 0x95: subA(l); cycles = 4; break;
            case 0x96: subA(mmu.readByte(getHL())); cycles = 8; break;
            case 0x97: subA(a); cycles = 4; break;

            case 0x98: sbcA(b); cycles = 4; break;
            case 0x99: sbcA(c); cycles = 4; break;
            case 0x9A: sbcA(d); cycles = 4; break;
            case 0x9B: sbcA(e); cycles = 4; break;
            case 0x9C: sbcA(h); cycles = 4; break;
            case 0x9D: sbcA(l); cycles = 4; break;
            case 0x9E: sbcA(mmu.readByte(getHL())); cycles = 8; break;
            case 0x9F: sbcA(a); cycles = 4; break;

            case 0xA0: andA(b); cycles = 4; break;
            case 0xA1: andA(c); cycles = 4; break;
            case 0xA2: andA(d); cycles = 4; break;
            case 0xA3: andA(e); cycles = 4; break;
            case 0xA4: andA(h); cycles = 4; break;
            case 0xA5: andA(l); cycles = 4; break;
            case 0xA6: andA(mmu.readByte(getHL())); cycles = 8; break;
            case 0xA7: andA(a); cycles = 4; break;

            case 0xA8: xorA(b); cycles = 4; break;
            case 0xA9: xorA(c); cycles = 4; break;
            case 0xAA: xorA(d); cycles = 4; break;
            case 0xAB: xorA(e); cycles = 4; break;
            case 0xAC: xorA(h); cycles = 4; break;
            case 0xAD: xorA(l); cycles = 4; break;
            case 0xAE: xorA(mmu.readByte(getHL())); cycles = 8; break;
            case 0xAF: xorA(a); cycles = 4; break;

            case 0xB0: orA(b); cycles = 4; break;
            case 0xB1: orA(c); cycles = 4; break;
            case 0xB2: orA(d); cycles = 4; break;
            case 0xB3: orA(e); cycles = 4; break;
            case 0xB4: orA(h); cycles = 4; break;
            case 0xB5: orA(l); cycles = 4; break;
            case 0xB6: orA(mmu.readByte(getHL())); cycles = 8; break;
            case 0xB7: orA(a); cycles = 4; break;

            case 0xB8: cpA(b); cycles = 4; break;
            case 0xB9: cpA(c); cycles = 4; break;
            case 0xBA: cpA(d); cycles = 4; break;
            case 0xBB: cpA(e); cycles = 4; break;
            case 0xBC: cpA(h); cycles = 4; break;
            case 0xBD: cpA(l); cycles = 4; break;
            case 0xBE: cpA(mmu.readByte(getHL())); cycles = 8; break;
            case 0xBF: cpA(a); cycles = 4; break;

            // RET NZ
            case 0xC0: if (!getZeroFlag()) { ret(); cycles = 20; } else { cycles = 8; } break;
            // POP BC
            case 0xC1: setBC(popWord()); cycles = 12; break;
            // JP NZ,a16
            case 0xC2: if (!getZeroFlag()) { jp_unconditional(); cycles = 16; } else { fetchWord(); cycles = 12; } break;
            // JP a16
            case 0xC3: jp_unconditional(); cycles = 16; break;
            // CALL NZ,a16
            case 0xC4: if (!getZeroFlag()) { call_unconditional(); cycles = 24; } else { fetchWord(); cycles = 12; } break;
            // PUSH BC
            case 0xC5: pushWord(getBC()); cycles = 16; break;
            // ADD A,d8
            case 0xC6: addA(fetch()); cycles = 8; break;
            // RST 00H
            case 0xC7: rst(0x00); cycles = 16; break;

            // RET Z
            case 0xC8: if (getZeroFlag()) { ret(); cycles = 20; } else { cycles = 8; } break;
            // RET
            case 0xC9: ret(); cycles = 16; break;
            // JP Z,a16
            case 0xCA: if (getZeroFlag()) { jp_unconditional(); cycles = 16; } else { fetchWord(); cycles = 12; } break;
            // CB prefix
            case 0xCB: decodeCB(); break;
            // CALL Z,a16
            case 0xCC: if (getZeroFlag()) { call_unconditional(); cycles = 24; } else { fetchWord(); cycles = 12; } break;
            // CALL a16
            case 0xCD: call_unconditional(); cycles = 24; break;
            // ADC A,d8
            case 0xCE: adcA(fetch()); cycles = 8; break;
            // RST 08H
            case 0xCF: rst(0x08); cycles = 16; break;

            // RET NC
            case 0xD0: if (!getCarryFlag()) { ret(); cycles = 20; } else { cycles = 8; } break;
            // POP DE
            case 0xD1: setDE(popWord()); cycles = 12; break;
            // JP NC,a16
            case 0xD2: if (!getCarryFlag()) { jp_unconditional(); cycles = 16; } else { fetchWord(); cycles = 12; } break;
            // Opcode 0xD3 (OUT (n), A) não existe no Game Boy
            case 0xD3:
                System.err.println(String.format("Illegal opcode: 0xD3 at PC: 0x%04X", (pc-1)&0xFFFF));
                halted = true; cycles = 4; break;
            // CALL NC,a16
            case 0xD4: if (!getCarryFlag()) { call_unconditional(); cycles = 24; } else { fetchWord(); cycles = 12; } break;
            // PUSH DE
            case 0xD5: pushWord(getDE()); cycles = 16; break;
            // SUB A,d8
            case 0xD6: subA(fetch()); cycles = 8; break;
            // RST 10H
            case 0xD7: rst(0x10); cycles = 16; break;

            // RET C
            case 0xD8: if (getCarryFlag()) { ret(); cycles = 20; } else { cycles = 8; } break;
            // RETI
            case 0xD9: ret(); ime = true; cycles = 16; break;
            // JP C,a16
            case 0xDA: if (getCarryFlag()) { jp_unconditional(); cycles = 16; } else { fetchWord(); cycles = 12; } break;
            // Opcode 0xDB (IN A, (n)) não existe no Game Boy
            case 0xDB:
                System.err.println(String.format("Illegal opcode: 0xDB at PC: 0x%04X", (pc-1)&0xFFFF));
                halted = true; cycles = 4; break;
            // CALL C,a16
            case 0xDC: if (getCarryFlag()) { call_unconditional(); cycles = 24; } else { fetchWord(); cycles = 12; } break;
            // Opcode 0xDD não existe (seria prefixo IX para Z80 completo)
            case 0xDD:
                System.err.println(String.format("Illegal opcode (Z80 IX prefix): 0xDD at PC: 0x%04X", (pc-1)&0xFFFF));
                halted = true; cycles = 4; break;
            // SBC A,d8
            case 0xDE: sbcA(fetch()); cycles = 8; break;
            // RST 18H
            case 0xDF: rst(0x18); cycles = 16; break;

            // LDH (a8),A  -> LD (0xFF00+a8),A
            case 0xE0: mmu.writeByte(0xFF00 + fetch(), a); cycles = 12; break;
            // POP HL
            case 0xE1: setHL(popWord()); cycles = 12; break;
            // LD (C),A -> LD (0xFF00+C),A
            case 0xE2: mmu.writeByte(0xFF00 + (c & 0xFF), a); cycles = 8; break;
            // Opcodes 0xE3, 0xE4 não existem
            case 0xE3: case 0xE4:
                System.err.println(String.format("Illegal opcode: 0x%02X at PC: 0x%04X", opcode, (pc-1)&0xFFFF));
                halted = true; cycles = 4; break;
            // PUSH HL
            case 0xE5: pushWord(getHL()); cycles = 16; break;
            // AND A,d8
            case 0xE6: andA(fetch()); cycles = 8; break;
            // RST 20H
            case 0xE7: rst(0x20); cycles = 16; break;
            // ADD SP,r8 (signed)
            case 0xE8: addSP_r8(); cycles = 16; break;
            // JP HL
            case 0xE9: pc = getHL(); cycles = 4; break;
            // LD (a16),A
            case 0xEA: mmu.writeByte(fetchWord(), a); cycles = 16; break;
            // Opcode 0xEB: EX DE,HL
            case 0xEB:
                int tempDE = getDE();
                setDE(getHL());
                setHL(tempDE);
                cycles = 4;
                break;
            // Opcodes 0xEC, 0xED não existem
            case 0xEC: case 0xED:
                System.err.println(String.format("Illegal opcode: 0x%02X at PC: 0x%04X", opcode, (pc-1)&0xFFFF));
                halted = true; cycles = 4; break;
            // XOR A,d8
            case 0xEE: xorA(fetch()); cycles = 8; break;
            // RST 28H
            case 0xEF: rst(0x28); cycles = 16; break;

            // LDH A,(a8) -> LD A,(0xFF00+a8)
            case 0xF0: a = mmu.readByte(0xFF00 + fetch()); cycles = 12; break;
            // POP AF
            case 0xF1: setAF(popWord()); cycles = 12; break;
            // LD A,(C) -> LD A,(0xFF00+C)
            case 0xF2: a = mmu.readByte(0xFF00 + (c & 0xFF)); cycles = 8; break;
            // DI
            case 0xF3: ime = false; cycles = 4; break;
            // Opcode 0xF4 não existe
            case 0xF4:
                System.err.println(String.format("Illegal opcode: 0xF4 at PC: 0x%04X", (pc-1)&0xFFFF));
                halted = true; cycles = 4; break;
            // PUSH AF
            case 0xF5: pushWord(getAF()); cycles = 16; break;
            // OR A,d8
            case 0xF6: orA(fetch()); cycles = 8; break;
            // RST 30H
            case 0xF7: rst(0x30); cycles = 16; break;
            // LD HL,SP+r8 (signed)
            case 0xF8: ldHL_SP_r8(); cycles = 12; break;
            // LD SP,HL
            case 0xF9: sp = getHL(); cycles = 8; break;
            // LD A,(a16)
            case 0xFA: a = mmu.readByte(fetchWord()); cycles = 16; break;
            // EI
            case 0xFB: ime = true; cycles = 4; break;
            // Opcodes 0xFC, 0xFD não existem
            case 0xFC: case 0xFD:
                System.err.println(String.format("Illegal opcode: 0x%02X at PC: 0x%04X", opcode, (pc-1)&0xFFFF));
                halted = true; cycles = 4; break;
            // CP A,d8
            case 0xFE: cpA(fetch()); cycles = 8; break;
            // RST 38H
            case 0xFF: rst(0x38); cycles = 16; break;


            default:
                System.err.println(String.format("Unimplemented opcode: 0x%02X at PC: 0x%04X", opcode, (pc-1)&0xFFFF));
                halted = true;
                cycles = 4;
                break;
        }
    }

    private void decodeCB() {
        int cbOpcode = fetch();
        cycles = 8; // A maioria das instruções CB leva 8 ciclos, exceto as (HL) que levam mais

        switch (cbOpcode & 0xF0) { // High nibble
            case 0x00: // RLC r, RRC r
                if ((cbOpcode & 0x08) == 0) { // RLC
                    switch (cbOpcode & 0x07) {
                        case 0x0: b = rlc(b); break;
                        case 0x1: c = rlc(c); break;
                        case 0x2: d = rlc(d); break;
                        case 0x3: e = rlc(e); break;
                        case 0x4: h = rlc(h); break;
                        case 0x5: l = rlc(l); break;
                        case 0x6: mmu.writeByte(getHL(), rlc(mmu.readByte(getHL()))); cycles = 16; break;
                        case 0x7: a = rlc(a); break;
                    }
                } else { // RRC
                    switch (cbOpcode & 0x07) {
                        case 0x0: b = rrc(b); break;
                        case 0x1: c = rrc(c); break;
                        case 0x2: d = rrc(d); break;
                        case 0x3: e = rrc(e); break;
                        case 0x4: h = rrc(h); break;
                        case 0x5: l = rrc(l); break;
                        case 0x6: mmu.writeByte(getHL(), rrc(mmu.readByte(getHL()))); cycles = 16; break;
                        case 0x7: a = rrc(a); break;
                    }
                }
                break;
            case 0x10: // RL r, RR r
                if ((cbOpcode & 0x08) == 0) { // RL
                    switch (cbOpcode & 0x07) {
                        case 0x0: b = rl(b); break;
                        case 0x1: c = rl(c); break;
                        case 0x2: d = rl(d); break;
                        case 0x3: e = rl(e); break;
                        case 0x4: h = rl(h); break;
                        case 0x5: l = rl(l); break;
                        case 0x6: mmu.writeByte(getHL(), rl(mmu.readByte(getHL()))); cycles = 16; break;
                        case 0x7: a = rl(a); break;
                    }
                } else { // RR
                    switch (cbOpcode & 0x07) {
                        case 0x0: b = rr(b); break;
                        case 0x1: c = rr(c); break;
                        case 0x2: d = rr(d); break;
                        case 0x3: e = rr(e); break;
                        case 0x4: h = rr(h); break;
                        case 0x5: l = rr(l); break;
                        case 0x6: mmu.writeByte(getHL(), rr(mmu.readByte(getHL()))); cycles = 16; break;
                        case 0x7: a = rr(a); break;
                    }
                }
                break;
            case 0x20: // SLA r, SRA r
                if ((cbOpcode & 0x08) == 0) { // SLA
                    switch (cbOpcode & 0x07) {
                        case 0x0: b = sla(b); break;
                        case 0x1: c = sla(c); break;
                        case 0x2: d = sla(d); break;
                        case 0x3: e = sla(e); break;
                        case 0x4: h = sla(h); break;
                        case 0x5: l = sla(l); break;
                        case 0x6: mmu.writeByte(getHL(), sla(mmu.readByte(getHL()))); cycles = 16; break;
                        case 0x7: a = sla(a); break;
                    }
                } else { // SRA
                    switch (cbOpcode & 0x07) {
                        case 0x0: b = sra(b); break;
                        case 0x1: c = sra(c); break;
                        case 0x2: d = sra(d); break;
                        case 0x3: e = sra(e); break;
                        case 0x4: h = sra(h); break;
                        case 0x5: l = sra(l); break;
                        case 0x6: mmu.writeByte(getHL(), sra(mmu.readByte(getHL()))); cycles = 16; break;
                        case 0x7: a = sra(a); break;
                    }
                }
                break;
            case 0x30: // SWAP r, SRL r
                if ((cbOpcode & 0x08) == 0) { // SWAP
                    switch (cbOpcode & 0x07) {
                        case 0x0: b = swap(b); break;
                        case 0x1: c = swap(c); break;
                        case 0x2: d = swap(d); break;
                        case 0x3: e = swap(e); break;
                        case 0x4: h = swap(h); break;
                        case 0x5: l = swap(l); break;
                        case 0x6: mmu.writeByte(getHL(), swap(mmu.readByte(getHL()))); cycles = 16; break;
                        case 0x7: a = swap(a); break;
                    }
                } else { // SRL
                    switch (cbOpcode & 0x07) {
                        case 0x0: b = srl(b); break;
                        case 0x1: c = srl(c); break;
                        case 0x2: d = srl(d); break;
                        case 0x3: e = srl(e); break;
                        case 0x4: h = srl(h); break;
                        case 0x5: l = srl(l); break;
                        case 0x6: mmu.writeByte(getHL(), srl(mmu.readByte(getHL()))); cycles = 16; break;
                        case 0x7: a = srl(a); break;
                    }
                }
                break;
            // BIT b,r
            case 0x40: case 0x50: case 0x60: case 0x70:
                int bit = (cbOpcode >> 3) & 0x07;
                int regVal = 0;
                boolean fromHL = false;
                switch (cbOpcode & 0x07) {
                    case 0x0: regVal = b; break;
                    case 0x1: regVal = c; break;
                    case 0x2: regVal = d; break;
                    case 0x3: regVal = e; break;
                    case 0x4: regVal = h; break;
                    case 0x5: regVal = l; break;
                    case 0x6: regVal = mmu.readByte(getHL()); fromHL = true; break;
                    case 0x7: regVal = a; break;
                }
                bitTest(regVal, bit);
                cycles = fromHL ? 12 : 8; // BIT (HL) is 12 cycles
                break;
            // RES b,r
            case 0x80: case 0x90: case 0xA0: case 0xB0:
                bit = (cbOpcode >> 3) & 0x07;
                switch (cbOpcode & 0x07) {
                    case 0x0: b = res(b, bit); break;
                    case 0x1: c = res(c, bit); break;
                    case 0x2: d = res(d, bit); break;
                    case 0x3: e = res(e, bit); break;
                    case 0x4: h = res(h, bit); break;
                    case 0x5: l = res(l, bit); break;
                    case 0x6: mmu.writeByte(getHL(), res(mmu.readByte(getHL()), bit)); cycles = 16; break;
                    case 0x7: a = res(a, bit); break;
                }
                break;
            // SET b,r
            case 0xC0: case 0xD0: case 0xE0: case 0xF0:
                bit = (cbOpcode >> 3) & 0x07;
                switch (cbOpcode & 0x07) {
                    case 0x0: b = set(b, bit); break;
                    case 0x1: c = set(c, bit); break;
                    case 0x2: d = set(d, bit); break;
                    case 0x3: e = set(e, bit); break;
                    case 0x4: h = set(h, bit); break;
                    case 0x5: l = set(l, bit); break;
                    case 0x6: mmu.writeByte(getHL(), set(mmu.readByte(getHL()), bit)); cycles = 16; break;
                    case 0x7: a = set(a, bit); break;
                }
                break;
            default:
                System.err.println(String.format("Unimplemented CB opcode: 0x%02X", cbOpcode));
                halted = true;
                cycles = 8;
                break;
        }
    }

    // --- Métodos de Registradores ---
    private int getAF() { return (a << 8) | (f & 0xF0); }
    private void setAF(int val) { a = (val >> 8) & 0xFF; f = val & 0xF0; }

    private int getBC() { return (b << 8) | c; }
    private void setBC(int val) { b = (val >> 8) & 0xFF; c = val & 0xFF; }

    private int getDE() { return (d << 8) | e; }
    private void setDE(int val) { d = (val >> 8) & 0xFF; e = val & 0xFF; }

    private int getHL() { return (h << 8) | l; }
    private void setHL(int val) { h = (val >> 8) & 0xFF; l = val & 0xFF; }

    // --- Métodos de Flags ---
    private void setZeroFlag(boolean set) { f = set ? (f | ZERO_FLAG) : (f & ~ZERO_FLAG); }
    private boolean getZeroFlag() { return (f & ZERO_FLAG) != 0; }

    private void setSubtractFlag(boolean set) { f = set ? (f | SUBTRACT_FLAG) : (f & ~SUBTRACT_FLAG); }
    private boolean getSubtractFlag() { return (f & SUBTRACT_FLAG) != 0; }

    private void setHalfCarryFlag(boolean set) { f = set ? (f | HALF_CARRY_FLAG) : (f & ~HALF_CARRY_FLAG); }
    private boolean getHalfCarryFlag() { return (f & HALF_CARRY_FLAG) != 0; }

    private void setCarryFlag(boolean set) { f = set ? (f | CARRY_FLAG) : (f & ~CARRY_FLAG); }
    private boolean getCarryFlag() { return (f & CARRY_FLAG) != 0; }

    // --- Lógica de Instruções (exemplos) ---
    private int inc8(int val) {
        int result = (val + 1) & 0xFF;
        setZeroFlag(result == 0);
        setSubtractFlag(false);
        setHalfCarryFlag((val & 0x0F) == 0x0F);
        return result;
    }

    private int dec8(int val) {
        int result = (val - 1) & 0xFF;
        setZeroFlag(result == 0);
        setSubtractFlag(true);
        setHalfCarryFlag((val & 0x0F) == 0x00);
        return result;
    }

    private void addA(int val) {
        val &= 0xFF;
        int result = a + val;
        setZeroFlag((result & 0xFF) == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(((a & 0x0F) + (val & 0x0F)) > 0x0F);
        setCarryFlag(result > 0xFF);
        a = result & 0xFF;
    }

    private void adcA(int val) {
        val &= 0xFF;
        int carry = getCarryFlag() ? 1 : 0;
        int result = a + val + carry;
        setZeroFlag((result & 0xFF) == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(((a & 0x0F) + (val & 0x0F) + carry) > 0x0F);
        setCarryFlag(result > 0xFF);
        a = result & 0xFF;
    }

    private void subA(int val) {
        val &= 0xFF;
        int result = a - val;
        setZeroFlag((result & 0xFF) == 0);
        setSubtractFlag(true);
        setHalfCarryFlag((a & 0x0F) < (val & 0x0F));
        setCarryFlag(a < val);
        a = result & 0xFF;
    }

    private void sbcA(int val) {
        val &= 0xFF;
        int carry = getCarryFlag() ? 1 : 0;
        int result = a - val - carry;
        setZeroFlag((result & 0xFF) == 0);
        setSubtractFlag(true);
        setHalfCarryFlag(((a & 0x0F) - (val & 0x0F) - carry) < 0);
        setCarryFlag(result < 0);
        a = result & 0xFF;
    }


    private void andA(int val) {
        a &= (val & 0xFF);
        setZeroFlag(a == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(true);
        setCarryFlag(false);
    }

    private void orA(int val) {
        a |= (val & 0xFF);
        setZeroFlag(a == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(false);
    }

    private void xorA(int val) {
        a ^= (val & 0xFF);
        setZeroFlag(a == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(false);
    }

    private void cpA(int val) {
        val &= 0xFF;
        int result = a - val;
        setZeroFlag((result & 0xFF) == 0);
        setSubtractFlag(true);
        setHalfCarryFlag((a & 0x0F) < (val & 0x0F));
        setCarryFlag(a < val);
    }

    private void addHL(int val16) {
        int hl = getHL();
        int result = hl + val16;
        setSubtractFlag(false);
        setHalfCarryFlag(((hl & 0xFFF) + (val16 & 0xFFF)) > 0xFFF);
        setCarryFlag(result > 0xFFFF);
        setHL(result & 0xFFFF);
    }

    private void addSP_r8() {
        byte offset = (byte) fetch();
        int result = sp + offset;
        setZeroFlag(false);
        setSubtractFlag(false);
        setHalfCarryFlag(((sp & 0x0F) + (offset & 0x0F)) > 0x0F);
        setCarryFlag(((sp & 0xFF) + (offset & 0xFF)) > 0xFF);
        sp = result & 0xFFFF;
    }

    private void ldHL_SP_r8() {
        byte offset = (byte) fetch();
        int result = sp + offset;
        setZeroFlag(false);
        setSubtractFlag(false);
        setHalfCarryFlag(((sp & 0x0F) + (offset & 0x0F)) > 0x0F);
        setCarryFlag(((sp & 0xFF) + (offset & 0xFF)) > 0xFF);
        setHL(result & 0xFFFF);
    }


    private void pushWord(int word) {
        sp = (sp - 1) & 0xFFFF;
        mmu.writeByte(sp, (word >> 8) & 0xFF);
        sp = (sp - 1) & 0xFFFF;
        mmu.writeByte(sp, word & 0xFF);
    }

    private int popWord() {
        int lsb = mmu.readByte(sp);
        sp = (sp + 1) & 0xFFFF;
        int msb = mmu.readByte(sp);
        sp = (sp + 1) & 0xFFFF;
        return (msb << 8) | lsb;
    }

    private void jr_unconditional() {
        byte offset = (byte) fetch();
        pc = (pc + offset) & 0xFFFF;
    }

    private void jp_unconditional() {
        pc = fetchWord();
    }

    private void call_unconditional() {
        int callAddr = fetchWord();
        pushWord(pc);
        pc = callAddr;
    }

    private void ret() {
        pc = popWord();
    }


    private void rst(int addr) {
        pushWord(pc);
        pc = addr;
    }

    private void rlca() {
        boolean carry = (a & 0x80) != 0;
        a = ((a << 1) | (carry ? 1 : 0)) & 0xFF;
        setZeroFlag(false);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(carry);
    }

    private void rrca() {
        boolean carry = (a & 0x01) != 0;
        a = ((a >> 1) | (carry ? 0x80 : 0)) & 0xFF;
        setZeroFlag(false);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(carry);
    }

    private void rla() {
        boolean oldCarry = getCarryFlag();
        boolean newCarry = (a & 0x80) != 0;
        a = ((a << 1) | (oldCarry ? 1 : 0)) & 0xFF;
        setZeroFlag(false);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(newCarry);
    }

    private void rra() {
        boolean oldCarry = getCarryFlag();
        boolean newCarry = (a & 0x01) != 0;
        a = ((a >> 1) | (oldCarry ? 0x80 : 0)) & 0xFF;
        setZeroFlag(false);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(newCarry);
    }

    private int rlc(int val) {
        boolean carry = (val & 0x80) != 0;
        val = ((val << 1) | (carry ? 1 : 0)) & 0xFF;
        setZeroFlag(val == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(carry);
        return val;
    }

    private int rrc(int val) {
        boolean carry = (val & 0x01) != 0;
        val = ((val >> 1) | (carry ? 0x80 : 0)) & 0xFF;
        setZeroFlag(val == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(carry);
        return val;
    }

    private int rl(int val) {
        boolean oldCarry = getCarryFlag();
        boolean newCarry = (val & 0x80) != 0;
        val = ((val << 1) | (oldCarry ? 1 : 0)) & 0xFF;
        setZeroFlag(val == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(newCarry);
        return val;
    }

    private int rr(int val) {
        boolean oldCarry = getCarryFlag();
        boolean newCarry = (val & 0x01) != 0;
        val = ((val >> 1) | (oldCarry ? 0x80 : 0)) & 0xFF;
        setZeroFlag(val == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(newCarry);
        return val;
    }

    private int sla(int val) {
        boolean carry = (val & 0x80) != 0;
        val = (val << 1) & 0xFF;
        setZeroFlag(val == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(carry);
        return val;
    }

    private int sra(int val) {
        boolean carry = (val & 0x01) != 0;
        int msb = val & 0x80;
        val = ((val >> 1) | msb) & 0xFF;
        setZeroFlag(val == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(carry);
        return val;
    }

    private int srl(int val) {
        boolean carry = (val & 0x01) != 0;
        val = (val >> 1) & 0xFF;
        setZeroFlag(val == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(carry);
        return val;
    }

    private int swap(int val) {
        val = (((val & 0x0F) << 4) | ((val & 0xF0) >> 4)) & 0xFF;
        setZeroFlag(val == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(false);
        return val;
    }

    private void bitTest(int val, int bit) {
        setZeroFlag((val & (1 << bit)) == 0);
        setSubtractFlag(false);
        setHalfCarryFlag(true);
    }

    private int res(int val, int bit) {
        return val & ~(1 << bit);
    }

    private int set(int val, int bit) {
        return val | (1 << bit);
    }

    private void daa() {
        int correction = 0;
        boolean originalCarry = getCarryFlag();

        if (getSubtractFlag()) {
            if (getHalfCarryFlag()) {
                correction = (correction + 0x06) & 0xFF;
            }
            if (originalCarry) {
                correction = (correction + 0x60) & 0xFF;
            }
            a = (a - correction) & 0xFF;
        } else {
            if ((a & 0x0F) > 0x09 || getHalfCarryFlag()) {
                correction += 0x06;
            }
            if (a > 0x99 || originalCarry) {
                correction += 0x60;
                setCarryFlag(true);
            } else {
                setCarryFlag(false);
            }
            a = (a + correction) & 0xFF;
        }
        setZeroFlag(a == 0);
        setHalfCarryFlag(false);
    }


    private void cpl() {
        a = (~a) & 0xFF;
        setSubtractFlag(true);
        setHalfCarryFlag(true);
    }

    private void scf() {
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(true);
    }

    private void ccf() {
        setSubtractFlag(false);
        setHalfCarryFlag(false);
        setCarryFlag(!getCarryFlag());
    }


    public void handleInterrupts() {
        if (!ime && !halted) {
            return;
        }

        byte IE = (byte) mmu.readByte(0xFFFF);
        byte IF = (byte) mmu.readByte(0xFF0F);

        byte requestedAndEnabled = (byte) (IE & IF & 0x1F);

        if (requestedAndEnabled == 0) {
            return;
        }

        halted = false;

        if (!ime) {
            return;
        }

        ime = false;
        cycles = 0;


        if ((requestedAndEnabled & 0x01) != 0) { // VBlank (Bit 0, highest priority)
            mmu.writeByte(0xFF0F, IF & ~0x01);
            rst(0x0040);
            cycles = 20;
        } else if ((requestedAndEnabled & 0x02) != 0) { // LCD STAT (Bit 1)
            mmu.writeByte(0xFF0F, IF & ~0x02);
            rst(0x0048);
            cycles = 20;
        } else if ((requestedAndEnabled & 0x04) != 0) { // Timer (Bit 2)
            mmu.writeByte(0xFF0F, IF & ~0x04);
            rst(0x0050);
            cycles = 20;
        } else if ((requestedAndEnabled & 0x08) != 0) { // Serial (Bit 3)
            mmu.writeByte(0xFF0F, IF & ~0x08);
            rst(0x0058);
            cycles = 20;
        } else if ((requestedAndEnabled & 0x10) != 0) { // Joypad (Bit 4, lowest priority)
            mmu.writeByte(0xFF0F, IF & ~0x10);
            rst(0x0060);
            cycles = 20;
        } else {
            ime = true;
        }
    }

    public boolean isHalted() {
        return halted;
    }

    public String getStatus() {
        return String.format("A:%02X F:%02X B:%02X C:%02X D:%02X E:%02X H:%02X L:%02X SP:%04X PC:%04X IME:%b Z:%d N:%d H:%d C:%d",
                a, f, b, c, d, e, h, l, sp, pc, ime,
                getZeroFlag() ? 1:0, getSubtractFlag() ? 1:0, getHalfCarryFlag() ? 1:0, getCarryFlag() ? 1:0);
    }
}