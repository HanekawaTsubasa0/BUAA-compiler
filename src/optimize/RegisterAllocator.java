package optimize;

import backend.mips.Register;
import midend.llvm.instr.Instr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class RegisterAllocator {
    private final IrFunction irFunction;
    private final ArrayList<Register> registerSet;
    private final HashMap<Register, IrValue> registerValueMap;
    private final HashMap<IrValue, Register> valueRegisterMap;

    public RegisterAllocator(IrFunction irFunction) {
        this.irFunction = irFunction;
        this.registerValueMap = new HashMap<>();
        this.valueRegisterMap = irFunction.GetValueRegisterMap();
        this.registerSet = Register.GetUsAbleRegisters();
    }

    public void Allocate(IrBasicBlock entryBlock) {
        HashMap<IrValue, Instr> lastUseMap = new HashMap<>();
        HashSet<IrValue> defineSet = new HashSet<>();
        HashSet<IrValue> neverUseAfterSet = new HashSet<>();

        this.RecordLastUse(entryBlock, lastUseMap);
        this.AllocateOneBlock(entryBlock, lastUseMap, defineSet, neverUseAfterSet);
        for (IrBasicBlock childBlock : entryBlock.GetDirectDominateBlocks()) {
            this.AllocateChildBlock(childBlock);
        }
        this.FreeDefineValueRegister(defineSet);
        this.ReCoverRegisterValueMap(defineSet, neverUseAfterSet);
    }

    private void RecordLastUse(IrBasicBlock irBasicBlock, HashMap<IrValue, Instr> lastUseMap) {
        for (Instr instr : irBasicBlock.GetInstrList()) {
            for (IrValue useValue : instr.GetUseValueList()) {
                lastUseMap.put(useValue, instr);
            }
        }
    }

    private void AllocateOneBlock(IrBasicBlock irBasicBlock, HashMap<IrValue, Instr> lastUseMap,
                                  HashSet<IrValue> defineSet, HashSet<IrValue> neverUseAfterSet) {
        for (Instr instr : irBasicBlock.GetInstrList()) {
            this.CheckAndFreeRegister(instr, lastUseMap, neverUseAfterSet);
            this.AllocateInstr(instr, defineSet);
        }
    }

    private void CheckAndFreeRegister(Instr instr, HashMap<IrValue, Instr> lastUseMap,
                                      HashSet<IrValue> neverUseAfterSet) {
        if (instr instanceof PhiInstr) {
            return;
        }
        ArrayList<IrValue> useValueList = instr.GetUseValueList();
        for (IrValue useValue : useValueList) {
            if (this.valueRegisterMap.containsKey(useValue) &&
                lastUseMap.get(useValue) == instr &&
                !instr.IsBlockOutValue(useValue)) {
                this.registerValueMap.remove(this.valueRegisterMap.get(useValue));
                neverUseAfterSet.add(useValue);
            }
        }
    }

    private void AllocateInstr(Instr instr, HashSet<IrValue> defineSet) {
        if (!instr.DefValue()) {
            return;
        }

        defineSet.add(instr);
        Set<Register> allocatedRegister = this.registerValueMap.keySet();
        for (Register register : this.registerSet) {
            if (!allocatedRegister.contains(register)) {
                this.registerValueMap.put(register, instr);
                this.valueRegisterMap.put(instr, register);
                break;
            }
        }
    }

    private void AllocateChildBlock(IrBasicBlock visitBlock) {
        HashMap<Register, IrValue> bufferMap = new HashMap<>();
        Set<Register> registerSet = new HashSet<>(this.registerValueMap.keySet());
        for (Register register : registerSet) {
            IrValue registerValue = this.registerValueMap.get(register);
            if (!visitBlock.GetInValueSet().contains(registerValue)) {
                bufferMap.put(register, registerValue);
                this.registerValueMap.remove(register);
            }
        }
        this.Allocate(visitBlock);
        for (Register register : bufferMap.keySet()) {
            this.registerValueMap.put(register, bufferMap.get(register));
        }
    }

    private void FreeDefineValueRegister(HashSet<IrValue> defineSet) {
        for (IrValue value : defineSet) {
            if (this.valueRegisterMap.containsKey(value) && defineSet.contains(value)) {
                this.registerValueMap.remove(this.valueRegisterMap.get(value));
            }
        }
    }

    private void ReCoverRegisterValueMap(HashSet<IrValue> defineSet,
                                         HashSet<IrValue> neverUseAfterSet) {
        for (IrValue value : neverUseAfterSet) {
            if (this.valueRegisterMap.containsKey(value) && !defineSet.contains(value)) {
                this.registerValueMap.put(this.valueRegisterMap.get(value), value);
            }
        }
    }
}
