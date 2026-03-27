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
import midend.llvm.type.IrType;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrGlobalValue;
import midend.llvm.value.IrParameter;
import midend.llvm.value.IrValue;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

public class LoadCse extends Optimizer {
    private final HashMap<IrFunction, Boolean> writeCache = new HashMap<>();

    @Override
    public void Optimize() {
        this.writeCache.clear();
        for (IrFunction function : irModule.GetFunctions()) {
            if (function.GetBasicBlocks().isEmpty()) {
                continue;
            }
            PropagateInFunction(function);
        }
    }

    private void PropagateInFunction(IrFunction function) {
        HashMap<IrBasicBlock, HashMap<MemoryKey, IrValue>> inMap = new HashMap<>();
        HashMap<IrBasicBlock, HashMap<MemoryKey, IrValue>> outMap = new HashMap<>();

        ArrayDeque<IrBasicBlock> worklist = new ArrayDeque<>();
        worklist.add(function.GetBasicBlocks().get(0));

        while (!worklist.isEmpty()) {
            IrBasicBlock block = worklist.poll();
            HashMap<MemoryKey, IrValue> newIn = MergePreds(block, inMap, outMap);
            HashMap<MemoryKey, IrValue> newOut = TransferBlock(block, newIn, false);

            HashMap<MemoryKey, IrValue> oldIn = inMap.get(block);
            HashMap<MemoryKey, IrValue> oldOut = outMap.get(block);
            if (!MapEquals(oldIn, newIn) || !MapEquals(oldOut, newOut)) {
                inMap.put(block, newIn);
                outMap.put(block, newOut);
                for (IrBasicBlock next : block.GetNextBlocks()) {
                    worklist.add(next);
                }
            }
        }

        for (IrBasicBlock block : function.GetBasicBlocks()) {
            HashMap<MemoryKey, IrValue> start =
                inMap.getOrDefault(block, MergePreds(block, inMap, outMap));
            TransferBlock(block, start, true);
        }
    }

    private HashMap<MemoryKey, IrValue> MergePreds(
        IrBasicBlock block,
        HashMap<IrBasicBlock, HashMap<MemoryKey, IrValue>> inMap,
        HashMap<IrBasicBlock, HashMap<MemoryKey, IrValue>> outMap) {

        if (block.IsEntryBlock()) {
            return new HashMap<>();
        }

        boolean first = true;
        HashMap<MemoryKey, IrValue> result = new HashMap<>();
        for (IrBasicBlock pred : block.GetBeforeBlocks()) {
            HashMap<MemoryKey, IrValue> predOut = outMap.get(pred);
            if (predOut == null) {
                predOut = new HashMap<>();
            }
            if (first) {
                result = new HashMap<>(predOut);
                first = false;
            } else {
                IntersectMap(result, predOut);
            }
        }
        return result;
    }

    private void IntersectMap(HashMap<MemoryKey, IrValue> base,
                              HashMap<MemoryKey, IrValue> other) {
        Iterator<MemoryKey> iterator = base.keySet().iterator();
        while (iterator.hasNext()) {
            MemoryKey key = iterator.next();
            IrValue otherValue = other.get(key);
            if (otherValue == null || !IsSameValue(otherValue, base.get(key))) {
                iterator.remove();
            }
        }
    }

    private boolean MapEquals(HashMap<MemoryKey, IrValue> a, HashMap<MemoryKey, IrValue> b) {
        if (a == null) {
            return b == null || b.isEmpty();
        }
        if (b == null) {
            return a.isEmpty();
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (MemoryKey key : a.keySet()) {
            IrValue valueA = a.get(key);
            IrValue valueB = b.get(key);
            if (valueB == null || !IsSameValue(valueA, valueB)) {
                return false;
            }
        }
        return true;
    }

    private HashMap<MemoryKey, IrValue> TransferBlock(IrBasicBlock block,
                                                      HashMap<MemoryKey, IrValue> in,
                                                      boolean rewriteLoads) {
        HashMap<MemoryKey, IrValue> current = new HashMap<>(in);
        HashMap<MemoryKey, IrValue> localStoreMap =
            rewriteLoads ? new HashMap<>() : null;
        Iterator<Instr> iterator = block.GetInstrList().iterator();
        while (iterator.hasNext()) {
            Instr instr = iterator.next();
            if (instr instanceof LoadInstr loadInstr) {
                HandleLoad(loadInstr, current, localStoreMap, iterator, rewriteLoads);
            } else if (instr instanceof StoreInstr storeInstr) {
                HandleStore(storeInstr, current, localStoreMap, rewriteLoads);
            } else if (instr instanceof CallInstr callInstr) {
                HandleCall(callInstr, current);
                if (rewriteLoads && localStoreMap != null) {
                    localStoreMap.clear();
                }
            } else if (instr instanceof IoInstr) {
                current.clear();
                if (rewriteLoads && localStoreMap != null) {
                    localStoreMap.clear();
                }
            }
        }
        return current;
    }

    private void HandleLoad(LoadInstr loadInstr, HashMap<MemoryKey, IrValue> map,
                            HashMap<MemoryKey, IrValue> localStoreMap,
                            Iterator<Instr> iterator, boolean rewrite) {
        PointerInfo info = ResolvePointer(loadInstr.GetPointer());
        if (info == null || info.unknownIndex) {
            return;
        }
        MemoryKey key = new MemoryKey(info.base, info.index);
        IrValue prev = null;
        if (rewrite && localStoreMap != null) {
            prev = localStoreMap.get(key);
            if (prev != null && !IsSameType(prev.GetIrType(), loadInstr.GetIrType())) {
                localStoreMap.remove(key);
                prev = null;
            }
        }
        if (prev == null) {
            prev = map.get(key);
            if (prev != null && !IsSameType(prev.GetIrType(), loadInstr.GetIrType())) {
                map.remove(key);
                prev = null;
            }
        }
        if (rewrite && prev != null) {
            loadInstr.ModifyAllUsersToNewValue(prev);
            loadInstr.RemoveAllValueUse();
            iterator.remove();
            map.put(key, prev);
            return;
        }
        map.put(key, loadInstr);
    }

    private void HandleStore(StoreInstr storeInstr, HashMap<MemoryKey, IrValue> map,
                             HashMap<MemoryKey, IrValue> localStoreMap, boolean rewrite) {
        PointerInfo info = ResolvePointer(storeInstr.GetAddressValue());
        if (info == null) {
            map.clear();
            if (rewrite && localStoreMap != null) {
                localStoreMap.clear();
            }
            return;
        }
        if (info.unknownIndex) {
            KillBase(map, info.base);
            if (rewrite && localStoreMap != null) {
                KillBase(localStoreMap, info.base);
            }
            return;
        }
        MemoryKey key = new MemoryKey(info.base, info.index);
        map.remove(key);
        if (rewrite && localStoreMap != null) {
            IrValue storedValue = GetStoredValue(storeInstr);
            if (storedValue == null) {
                localStoreMap.remove(key);
            } else {
                localStoreMap.put(key, storedValue);
            }
        }
    }

    private void HandleCall(CallInstr callInstr, HashMap<MemoryKey, IrValue> map) {
        IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
        if (callee == null || IsWriteFunction(callee, new HashSet<>())) {
            map.clear();
        }
    }

    private void KillBase(HashMap<MemoryKey, IrValue> map, IrValue base) {
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
        if (pointer instanceof IrGlobalValue) {
            return null;
        }
        if (pointer instanceof AllocateInstr) {
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

    private boolean IsSameValue(IrValue left, IrValue right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (!IsSameType(left.GetIrType(), right.GetIrType())) {
            return false;
        }
        if (left instanceof IrConstantInt leftConst && right instanceof IrConstantInt rightConst) {
            return leftConst.GetValue() == rightConst.GetValue();
        }
        return left.GetIrName().equals(right.GetIrName());
    }

    private IrValue GetStoredValue(StoreInstr storeInstr) {
        IrValue value = storeInstr.GetValueValue();
        IrValue address = storeInstr.GetAddressValue();
        if (!(address.GetIrType() instanceof IrPointerType pointerType)) {
            return null;
        }
        IrType targetType = pointerType.GetTargetType();
        if (!IsSameType(value.GetIrType(), targetType)) {
            return null;
        }
        return value;
    }

    private boolean IsSameType(IrType left, IrType right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.toString().equals(right.toString());
    }

    private boolean IsWriteFunction(IrFunction function, HashSet<IrFunction> visiting) {
        Boolean cached = this.writeCache.get(function);
        if (cached != null) {
            return cached;
        }
        if (visiting.contains(function)) {
            return false;
        }
        visiting.add(function);
        boolean writes = false;
        for (IrBasicBlock block : function.GetBasicBlocks()) {
            for (Instr instr : block.GetInstrList()) {
                if (instr instanceof StoreInstr || instr instanceof IoInstr) {
                    writes = true;
                    break;
                }
                if (instr instanceof CallInstr callInstr) {
                    IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
                    if (callee == null || IsWriteFunction(callee, visiting)) {
                        writes = true;
                        break;
                    }
                }
            }
            if (writes) {
                break;
            }
        }
        visiting.remove(function);
        this.writeCache.put(function, writes);
        return writes;
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
