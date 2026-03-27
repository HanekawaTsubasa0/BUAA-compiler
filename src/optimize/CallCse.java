package optimize;

import midend.llvm.instr.CallInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.LoadInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.instr.io.IoInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class CallCse extends Optimizer {
    private final HashMap<IrFunction, Boolean> pureCache = new HashMap<>();

    @Override
    public void Optimize() {
        pureCache.clear();
        for (IrFunction function : irModule.GetFunctions()) {
            if (function.GetBasicBlocks().isEmpty()) {
                continue;
            }
            VisitBlock(function.GetBasicBlocks().get(0), new HashMap<>());
        }
    }

    private void VisitBlock(IrBasicBlock block, HashMap<CallKey, IrValue> inMap) {
        HashMap<CallKey, IrValue> localMap = new HashMap<>(inMap);
        Iterator<Instr> iterator = block.GetInstrList().iterator();
        while (iterator.hasNext()) {
            Instr instr = iterator.next();
            if (!(instr instanceof CallInstr callInstr)) {
                continue;
            }
            if (callInstr.GetIrType().IsVoidType()) {
                continue;
            }
            IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
            if (callee == null || !IsPureFunction(callee, new HashSet<>())) {
                continue;
            }
            CallKey key = new CallKey(callee, callInstr.GetParamList());
            IrValue prev = localMap.get(key);
            if (prev != null) {
                callInstr.ModifyAllUsersToNewValue(prev);
                callInstr.RemoveAllValueUse();
                iterator.remove();
            } else {
                localMap.put(key, callInstr);
            }
        }

        for (IrBasicBlock child : block.GetDirectDominateBlocks()) {
            VisitBlock(child, localMap);
        }
    }

    private boolean IsPureFunction(IrFunction function, HashSet<IrFunction> visiting) {
        Boolean cached = pureCache.get(function);
        if (cached != null) {
            return cached;
        }
        if (visiting.contains(function)) {
            return true;
        }
        visiting.add(function);
        boolean pure = true;
        for (IrBasicBlock block : function.GetBasicBlocks()) {
            for (Instr instr : block.GetInstrList()) {
                if (instr instanceof LoadInstr || instr instanceof StoreInstr ||
                    instr instanceof IoInstr) {
                    pure = false;
                    break;
                }
                if (instr instanceof CallInstr callInstr) {
                    IrFunction callee = ResolveCallee(callInstr.GetTargetFunction());
                    if (callee == null || !IsPureFunction(callee, visiting)) {
                        pure = false;
                        break;
                    }
                }
            }
            if (!pure) {
                break;
            }
        }
        visiting.remove(function);
        pureCache.put(function, pure);
        return pure;
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

    private static class CallKey {
        private final IrFunction function;
        private final List<IrValue> params;

        private CallKey(IrFunction function, List<IrValue> params) {
            this.function = function;
            this.params = new ArrayList<>(params);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CallKey other)) {
                return false;
            }
            if (this.function != other.function) {
                return false;
            }
            if (this.params.size() != other.params.size()) {
                return false;
            }
            for (int i = 0; i < this.params.size(); i++) {
                if (this.params.get(i) != other.params.get(i)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(this.function);
            for (IrValue param : this.params) {
                hash = 31 * hash + System.identityHashCode(param);
            }
            return hash;
        }
    }
}
