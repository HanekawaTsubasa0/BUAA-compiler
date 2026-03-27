package optimize;

import midend.llvm.constant.IrConstantInt;
import midend.llvm.instr.AllocateInstr;
import midend.llvm.instr.CallInstr;
import midend.llvm.instr.GepInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.LoadInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.instr.io.IoInstr;
import midend.llvm.type.IrArrayType;
import midend.llvm.type.IrPointerType;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrGlobalValue;
import midend.llvm.value.IrParameter;
import midend.llvm.value.IrValue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

public class DeadStoreElim extends Optimizer {
    private final HashMap<IrFunction, Boolean> memoryFreeCache = new HashMap<>();

    @Override
    public void Optimize() {
        memoryFreeCache.clear();
        for (IrFunction function : irModule.GetFunctions()) {
            for (IrBasicBlock block : function.GetBasicBlocks()) {
                ProcessBlock(block);
            }
        }
    }

    private void ProcessBlock(IrBasicBlock block) {
        HashMap<MemoryKey, StoreInstr> lastStoreMap = new HashMap<>();
        HashSet<Instr> removeSet = new HashSet<>();
        for (Instr instr : block.GetInstrList()) {
            if (instr instanceof LoadInstr loadInstr) {
                PointerInfo info = ResolvePointer(loadInstr.GetPointer());
                if (info == null || info.unknownIndex) {
                    lastStoreMap.clear();
                    continue;
                }
                lastStoreMap.remove(new MemoryKey(info.base, info.index));
            } else if (instr instanceof StoreInstr storeInstr) {
                PointerInfo info = ResolvePointer(storeInstr.GetAddressValue());
                if (info == null) {
                    lastStoreMap.clear();
                    continue;
                }
                if (info.unknownIndex) {
                    KillBase(lastStoreMap, info.base);
                    continue;
                }
                MemoryKey key = new MemoryKey(info.base, info.index);
                StoreInstr prev = lastStoreMap.get(key);
                if (prev != null) {
                    removeSet.add(prev);
                }
                lastStoreMap.put(key, storeInstr);
            } else if (instr instanceof CallInstr callInstr) {
                IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
                if (callee == null || !IsMemoryFreeFunction(callee, new HashSet<>())) {
                    lastStoreMap.clear();
                }
            } else if (instr instanceof IoInstr) {
                lastStoreMap.clear();
            }
        }

        if (removeSet.isEmpty()) {
            return;
        }
        Iterator<Instr> iterator = block.GetInstrList().iterator();
        while (iterator.hasNext()) {
            Instr instr = iterator.next();
            if (removeSet.contains(instr)) {
                instr.RemoveAllValueUse();
                iterator.remove();
            }
        }
    }

    private void KillBase(HashMap<MemoryKey, StoreInstr> map, IrValue base) {
        Iterator<MemoryKey> iterator = map.keySet().iterator();
        while (iterator.hasNext()) {
            MemoryKey key = iterator.next();
            if (key.base == base) {
                iterator.remove();
            }
        }
    }

    private PointerInfo ResolvePointer(IrValue pointer) {
        if (pointer instanceof IrParameter) {
            return null;
        }
        if (pointer instanceof IrGlobalValue || pointer instanceof AllocateInstr) {
            IrPointerType pointerType = (IrPointerType) pointer.GetIrType();
            boolean isArray = pointerType.GetTargetType() instanceof IrArrayType;
            return new PointerInfo(pointer, isArray ? 0 : null, false, isArray);
        }
        if (pointer instanceof GepInstr gepInstr) {
            PointerInfo baseInfo = ResolvePointer(gepInstr.GetPointer());
            if (baseInfo == null) {
                return null;
            }
            Integer offset = GetConstOffset(gepInstr.GetOffset());
            if (offset == null) {
                return baseInfo.WithUnknownIndex();
            }
            if (baseInfo.unknownIndex) {
                return baseInfo.WithUnknownIndex();
            }
            if (!baseInfo.fromArray && baseInfo.index == null) {
                if (offset == 0) {
                    return baseInfo;
                }
                return null;
            }
            int baseIndex = baseInfo.index == null ? 0 : baseInfo.index;
            return new PointerInfo(baseInfo.base, baseIndex + offset, false, true);
        }
        return null;
    }

    private Integer GetConstOffset(IrValue value) {
        if (value instanceof IrConstantInt irConstantInt) {
            return irConstantInt.GetValue();
        }
        return null;
    }

    private boolean IsMemoryFreeFunction(IrFunction function, HashSet<IrFunction> visiting) {
        Boolean cached = memoryFreeCache.get(function);
        if (cached != null) {
            return cached;
        }
        if (visiting.contains(function)) {
            return true;
        }
        visiting.add(function);
        boolean memoryFree = true;
        for (IrBasicBlock block : function.GetBasicBlocks()) {
            for (Instr instr : block.GetInstrList()) {
                if (instr instanceof LoadInstr || instr instanceof StoreInstr ||
                    instr instanceof IoInstr) {
                    memoryFree = false;
                    break;
                }
                if (instr instanceof CallInstr callInstr) {
                    IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
                    if (callee == null || !IsMemoryFreeFunction(callee, visiting)) {
                        memoryFree = false;
                        break;
                    }
                }
            }
            if (!memoryFree) {
                break;
            }
        }
        visiting.remove(function);
        memoryFreeCache.put(function, memoryFree);
        return memoryFree;
    }

    private IrFunction ResolveCallee(IrFunction callee) {
        if (callee == null) {
            return null;
        }
        if (irModule.GetFunctions().contains(callee)) {
            return callee;
        }
        for (IrFunction irFunction : irModule.GetFunctions()) {
            if (irFunction.GetIrName().equals(callee.GetIrName())) {
                return irFunction;
            }
        }
        return null;
    }

    private static class MemoryKey {
        private final IrValue base;
        private final Integer index;

        private MemoryKey(IrValue base, Integer index) {
            this.base = base;
            this.index = index;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MemoryKey other)) {
                return false;
            }
            if (this.base != other.base) {
                return false;
            }
            if (this.index == null) {
                return other.index == null;
            }
            return this.index.equals(other.index);
        }

        @Override
        public int hashCode() {
            int baseHash = System.identityHashCode(this.base);
            return 31 * baseHash + (this.index == null ? 0 : this.index.hashCode());
        }
    }

    private static class PointerInfo {
        private final IrValue base;
        private final Integer index;
        private final boolean unknownIndex;
        private final boolean fromArray;

        private PointerInfo(IrValue base, Integer index, boolean unknownIndex, boolean fromArray) {
            this.base = base;
            this.index = index;
            this.unknownIndex = unknownIndex;
            this.fromArray = fromArray;
        }

        private PointerInfo WithUnknownIndex() {
            return new PointerInfo(this.base, this.index, true, this.fromArray);
        }
    }
}
