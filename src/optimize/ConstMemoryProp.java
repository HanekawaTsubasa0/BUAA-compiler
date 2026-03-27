package optimize;

import midend.llvm.constant.IrConstant;
import midend.llvm.constant.IrConstantArray;
import midend.llvm.constant.IrConstantInt;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

public class ConstMemoryProp extends Optimizer {
    private final HashMap<IrFunction, Boolean> writeCache = new HashMap<>();
    private final HashSet<IrGlobalValue> immutableGlobals = new HashSet<>();
    private final HashMap<MemoryKey, Integer> globalInitMap = new HashMap<>();

    @Override
    public void Optimize() {
        BuildGlobalInfo();
        for (IrFunction function : irModule.GetFunctions()) {
            PropagateInFunction(function);
        }
    }

    private void BuildGlobalInfo() {
        this.immutableGlobals.clear();
        this.globalInitMap.clear();
        this.writeCache.clear();

        HashSet<IrGlobalValue> globals = new HashSet<>(irModule.GetGlobalValues());
        HashSet<IrGlobalValue> mutableGlobals = new HashSet<>();
        boolean unknownStore = false;

        for (IrFunction function : irModule.GetFunctions()) {
            for (IrBasicBlock block : function.GetBasicBlocks()) {
                for (Instr instr : block.GetInstrList()) {
                    if (instr instanceof StoreInstr storeInstr) {
                        PointerInfo info = ResolvePointer(storeInstr.GetAddressValue());
                        if (info == null) {
                            unknownStore = true;
                            continue;
                        }
                        if (info.base instanceof IrGlobalValue globalValue) {
                            mutableGlobals.add(globalValue);
                        }
                    } else if (instr instanceof CallInstr callInstr) {
                        IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
                        if (callee == null || IsWriteFunction(callee, new HashSet<>())) {
                            for (IrValue param : callInstr.GetParamList()) {
                                PointerInfo info = ResolvePointer(param);
                                if (info != null && info.base instanceof IrGlobalValue globalValue) {
                                    mutableGlobals.add(globalValue);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (unknownStore) {
            mutableGlobals.addAll(globals);
        }

        for (IrGlobalValue globalValue : globals) {
            if (!mutableGlobals.contains(globalValue)) {
                this.immutableGlobals.add(globalValue);
            }
            AddGlobalInit(globalValue);
        }
    }

    private void AddGlobalInit(IrGlobalValue globalValue) {
        IrConstant constant = globalValue.GetConstant();
        if (constant instanceof IrConstantInt irConstantInt) {
            MemoryKey key = new MemoryKey(globalValue, null);
            this.globalInitMap.put(key, irConstantInt.GetValue());
        } else if (constant instanceof IrConstantArray array) {
            for (int i = 0; i < array.GetArraySize(); i++) {
                IrConstant element = array.GetElement(i);
                if (element instanceof IrConstantInt irConstantInt) {
                    MemoryKey key = new MemoryKey(globalValue, i);
                    this.globalInitMap.put(key, irConstantInt.GetValue());
                }
            }
        }
    }

    private void PropagateInFunction(IrFunction function) {
        HashMap<IrBasicBlock, HashMap<MemoryKey, Integer>> inMap = new HashMap<>();
        HashMap<IrBasicBlock, HashMap<MemoryKey, Integer>> outMap = new HashMap<>();

        ArrayDeque<IrBasicBlock> worklist = new ArrayDeque<>();
        if (!function.GetBasicBlocks().isEmpty()) {
            worklist.add(function.GetBasicBlocks().get(0));
        }

        while (!worklist.isEmpty()) {
            IrBasicBlock block = worklist.poll();
            HashMap<MemoryKey, Integer> newIn = MergePreds(block, inMap, outMap, function);
            HashMap<MemoryKey, Integer> newOut = TransferBlock(block, newIn, false);

            HashMap<MemoryKey, Integer> oldIn = inMap.get(block);
            HashMap<MemoryKey, Integer> oldOut = outMap.get(block);
            if (!MapEquals(oldIn, newIn) || !MapEquals(oldOut, newOut)) {
                inMap.put(block, newIn);
                outMap.put(block, newOut);
                for (IrBasicBlock next : block.GetNextBlocks()) {
                    worklist.add(next);
                }
            }
        }

        for (IrBasicBlock block : function.GetBasicBlocks()) {
            HashMap<MemoryKey, Integer> start =
                inMap.getOrDefault(block, MergePreds(block, inMap, outMap, function));
            TransferBlock(block, start, true);
        }
    }

    private HashMap<MemoryKey, Integer> MergePreds(
        IrBasicBlock block,
        HashMap<IrBasicBlock, HashMap<MemoryKey, Integer>> inMap,
        HashMap<IrBasicBlock, HashMap<MemoryKey, Integer>> outMap,
        IrFunction function) {

        if (block.IsEntryBlock()) {
            return GetEntryMap(function);
        }

        boolean first = true;
        HashMap<MemoryKey, Integer> result = new HashMap<>();
        for (IrBasicBlock pred : block.GetBeforeBlocks()) {
            HashMap<MemoryKey, Integer> predOut = outMap.get(pred);
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

    private HashMap<MemoryKey, Integer> GetEntryMap(IrFunction function) {
        HashMap<MemoryKey, Integer> map = new HashMap<>();
        if (function.IsMainFunction()) {
            map.putAll(this.globalInitMap);
        } else {
            for (MemoryKey key : this.globalInitMap.keySet()) {
                if (key.base instanceof IrGlobalValue global &&
                    this.immutableGlobals.contains(global)) {
                    map.put(key, this.globalInitMap.get(key));
                }
            }
        }
        return map;
    }

    private void IntersectMap(HashMap<MemoryKey, Integer> base,
                              HashMap<MemoryKey, Integer> other) {
        Iterator<MemoryKey> iterator = base.keySet().iterator();
        while (iterator.hasNext()) {
            MemoryKey key = iterator.next();
            Integer value = other.get(key);
            if (value == null || !value.equals(base.get(key))) {
                iterator.remove();
            }
        }
    }

    private boolean MapEquals(HashMap<MemoryKey, Integer> a, HashMap<MemoryKey, Integer> b) {
        if (a == null) {
            return b == null || b.isEmpty();
        }
        if (b == null) {
            return a.isEmpty();
        }
        return a.equals(b);
    }

    private HashMap<MemoryKey, Integer> TransferBlock(IrBasicBlock block,
                                                      HashMap<MemoryKey, Integer> in,
                                                      boolean rewriteLoads) {
        HashMap<MemoryKey, Integer> current = new HashMap<>(in);
        Iterator<Instr> iterator = block.GetInstrList().iterator();
        while (iterator.hasNext()) {
            Instr instr = iterator.next();
            if (instr instanceof StoreInstr storeInstr) {
                HandleStore(storeInstr, current);
            } else if (instr instanceof CallInstr callInstr) {
                HandleCall(callInstr, current);
            } else if (rewriteLoads && instr instanceof LoadInstr loadInstr) {
                HandleLoad(loadInstr, current, iterator);
            }
        }
        return current;
    }

    private void HandleLoad(LoadInstr loadInstr, HashMap<MemoryKey, Integer> map,
                            Iterator<Instr> iterator) {
        if (!loadInstr.GetIrType().IsInt32Type()) {
            return;
        }
        PointerInfo info = ResolvePointer(loadInstr.GetPointer());
        if (info == null || info.unknownIndex) {
            return;
        }
        MemoryKey key = new MemoryKey(info.base, info.index);
        Integer value = map.get(key);
        if (value == null) {
            return;
        }
        IrConstantInt constant = new IrConstantInt(value);
        loadInstr.ModifyAllUsersToNewValue(constant);
        loadInstr.RemoveAllValueUse();
        iterator.remove();
    }

    private void HandleStore(StoreInstr storeInstr, HashMap<MemoryKey, Integer> map) {
        PointerInfo info = ResolvePointer(storeInstr.GetAddressValue());
        if (info == null) {
            map.clear();
            return;
        }
        if (info.unknownIndex) {
            KillBase(map, info.base);
            return;
        }
        MemoryKey key = new MemoryKey(info.base, info.index);
        Integer value = GetConstValue(storeInstr.GetValueValue());
        if (value == null) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    private void HandleCall(CallInstr callInstr, HashMap<MemoryKey, Integer> map) {
        IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
        if (callee == null || IsWriteFunction(callee, new HashSet<>())) {
            map.clear();
        }
    }

    private Integer GetConstValue(IrValue value) {
        if (value instanceof IrConstantInt irConstantInt) {
            return irConstantInt.GetValue();
        }
        return null;
    }

    private void KillBase(HashMap<MemoryKey, Integer> map, IrValue base) {
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
        if (pointer instanceof IrGlobalValue || pointer instanceof midend.llvm.instr.AllocateInstr) {
            IrPointerType pointerType = (IrPointerType) pointer.GetIrType();
            boolean isArray = pointerType.GetTargetType() instanceof IrArrayType;
            return new PointerInfo(pointer, isArray ? 0 : null, false, isArray);
        }
        if (pointer instanceof GepInstr gepInstr) {
            PointerInfo baseInfo = ResolvePointer(gepInstr.GetPointer());
            if (baseInfo == null) {
                return null;
            }
            Integer offset = GetConstValue(gepInstr.GetOffset());
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
