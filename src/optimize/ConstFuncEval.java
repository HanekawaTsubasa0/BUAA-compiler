package optimize;

import midend.llvm.constant.IrConstantInt;
import midend.llvm.instr.AluInstr;
import midend.llvm.instr.BranchInstr;
import midend.llvm.instr.CallInstr;
import midend.llvm.instr.CompareInstr;
import midend.llvm.instr.ExtendInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.JumpInstr;
import midend.llvm.instr.LoadInstr;
import midend.llvm.instr.MoveInstr;
import midend.llvm.instr.ReturnInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.instr.TruncInstr;
import midend.llvm.instr.io.IoInstr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrParameter;
import midend.llvm.value.IrValue;
import Utils.Setting;

import java.util.ArrayList;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public class ConstFuncEval extends Optimizer {
    private static final int STEP_LIMIT = 10000;
    private final HashMap<IrFunction, Boolean> pureCache = new HashMap<>();
    private final HashMap<EvalKey, Integer> evalCache = new HashMap<>();
    private PrintStream debugOut;
    private String lastFailReason;

    @Override
    public void Optimize() {
        this.InitDebug();
        boolean changed = true;
        try {
            while (changed) {
                changed = false;
                for (IrFunction irFunction : irModule.GetFunctions()) {
                    for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                        Iterator<Instr> iterator = irBasicBlock.GetInstrList().iterator();
                        while (iterator.hasNext()) {
                            Instr instr = iterator.next();
                            if (!(instr instanceof CallInstr callInstr)) {
                                continue;
                            }
                            String callDesc = this.SafeCallDesc(callInstr);
                            if (callInstr.GetIrType().IsVoidType()) {
                                this.DebugSkip(irFunction, callDesc, "void return");
                                continue;
                            }
                            ArrayList<Integer> constArgs =
                                this.GetConstArgs(callInstr.GetParamList());
                            if (constArgs == null) {
                                this.DebugSkip(irFunction, callDesc, "non-constant args");
                                continue;
                            }
                            IrFunction callee =
                                this.ResolveCallee(callInstr.GetTargetFunction());
                            if (callee == null) {
                                this.DebugSkip(irFunction, callDesc, "callee not found");
                                continue;
                            }
                            this.ResetFailReason();
                            Integer result =
                                this.EvalFunction(callee, constArgs, new HashSet<>());
                            if (result == null) {
                                String reason = this.lastFailReason == null ?
                                    "unknown" : this.lastFailReason;
                                this.DebugSkip(irFunction, callDesc, reason);
                                continue;
                            }
                            callInstr.ModifyAllUsersToNewValue(new IrConstantInt(result));
                            callInstr.RemoveAllValueUse();
                            iterator.remove();
                            changed = true;
                            this.DebugSkip(irFunction, callDesc, "folded to " + result);
                        }
                    }
                }
            }
        } finally {
            this.CloseDebug();
        }
    }

    private void InitDebug() {
        if (!Setting.DEBUG) {
            return;
        }
        try {
            this.debugOut = new PrintStream(new FileOutputStream("const_eval_debug.txt"));
        } catch (IOException ignored) {
            this.debugOut = null;
        }
    }

    private void CloseDebug() {
        if (this.debugOut != null) {
            this.debugOut.close();
            this.debugOut = null;
        }
    }

    private void DebugSkip(IrFunction caller, String callDesc, String reason) {
        if (this.debugOut == null) {
            return;
        }
        this.debugOut.println("[" + caller.GetIrName() + "] " +
            callDesc + " -> " + reason);
    }

    private String SafeCallDesc(CallInstr callInstr) {
        try {
            return callInstr.toString();
        } catch (RuntimeException ignored) {
            return callInstr.GetIrName();
        }
    }

    private void ResetFailReason() {
        this.lastFailReason = null;
    }

    private void SetFailReason(String reason) {
        if (this.lastFailReason == null) {
            this.lastFailReason = reason;
        }
    }

    private ArrayList<Integer> GetConstArgs(ArrayList<IrValue> params) {
        ArrayList<Integer> constArgs = new ArrayList<>();
        for (IrValue param : params) {
            Integer value = this.GetConstValue(param, null);
            if (value == null) {
                return null;
            }
            constArgs.add(value);
        }
        return constArgs;
    }

    private Integer EvalFunction(IrFunction irFunction, ArrayList<Integer> args,
                                 HashSet<EvalKey> stack) {
        if (irFunction == null) {
            this.SetFailReason("callee null");
            return null;
        }
        if (!this.IsPureFunction(irFunction, new HashSet<>())) {
            this.SetFailReason("not pure");
            return null;
        }
        EvalKey key = new EvalKey(irFunction, args);
        if (stack.contains(key)) {
            this.SetFailReason("recursive cycle");
            return null;
        }
        if (evalCache.containsKey(key)) {
            return evalCache.get(key);
        }
        stack.add(key);
        Integer result = this.InterpretFunction(irFunction, args, stack);
        stack.remove(key);
        if (result != null) {
            evalCache.put(key, result);
        }
        return result;
    }

    private Integer InterpretFunction(IrFunction irFunction, ArrayList<Integer> args,
                                      HashSet<EvalKey> stack) {
        ArrayList<IrParameter> params = irFunction.GetParameterList();
        if (params.size() != args.size()) {
            this.SetFailReason("param size mismatch");
            return null;
        }

        Map<IrValue, Integer> valueMap = new HashMap<>();
        for (int i = 0; i < params.size(); i++) {
            valueMap.put(params.get(i), args.get(i));
        }

        IrBasicBlock currentBlock = irFunction.GetBasicBlocks().get(0);
        IrBasicBlock prevBlock = null;
        int steps = 0;
        while (true) {
            if (steps++ > STEP_LIMIT) {
                this.SetFailReason("step limit");
                return null;
            }
            ArrayList<Instr> instrList = currentBlock.GetInstrList();
            Map<IrValue, Integer> phiInputMap = new HashMap<>(valueMap);
            Map<PhiInstr, Integer> pendingPhi = new HashMap<>();
            boolean jumped = false;
            for (Instr instr : instrList) {
                if (instr instanceof PhiInstr phiInstr) {
                    Integer phiValue = this.EvalPhi(phiInstr, prevBlock, phiInputMap);
                    if (phiValue == null) {
                        this.SetFailReason("phi unknown");
                        return null;
                    }
                    pendingPhi.put(phiInstr, phiValue);
                    continue;
                }
                if (!pendingPhi.isEmpty()) {
                    for (Map.Entry<PhiInstr, Integer> entry : pendingPhi.entrySet()) {
                        valueMap.put(entry.getKey(), entry.getValue());
                    }
                    pendingPhi.clear();
                }
                if (instr instanceof AluInstr aluInstr) {
                    Integer valueL = this.GetConstValue(aluInstr.GetValueL(), valueMap);
                    Integer valueR = this.GetConstValue(aluInstr.GetValueR(), valueMap);
                    if (valueL == null || valueR == null) {
                        this.SetFailReason("alu operand unknown");
                        return null;
                    }
                    Integer result = this.EvalAlu(aluInstr, valueL, valueR);
                    if (result == null) {
                        this.SetFailReason("alu error");
                        return null;
                    }
                    valueMap.put(aluInstr, result);
                } else if (instr instanceof CompareInstr compareInstr) {
                    Integer valueL = this.GetConstValue(compareInstr.GetValueL(), valueMap);
                    Integer valueR = this.GetConstValue(compareInstr.GetValueR(), valueMap);
                    if (valueL == null || valueR == null) {
                        this.SetFailReason("compare operand unknown");
                        return null;
                    }
                    valueMap.put(compareInstr, this.EvalCompare(compareInstr, valueL, valueR));
                } else if (instr instanceof ExtendInstr extendInstr) {
                    Integer value = this.GetConstValue(extendInstr.GetOriginValue(), valueMap);
                    if (value == null) {
                        this.SetFailReason("extend operand unknown");
                        return null;
                    }
                    valueMap.put(extendInstr, value);
                } else if (instr instanceof TruncInstr truncInstr) {
                    Integer value = this.GetConstValue(truncInstr.GetOriginValue(), valueMap);
                    if (value == null) {
                        this.SetFailReason("trunc operand unknown");
                        return null;
                    }
                    valueMap.put(truncInstr, value);
                } else if (instr instanceof MoveInstr moveInstr) {
                    Integer value = this.GetConstValue(moveInstr.GetSrcValue(), valueMap);
                    if (value == null) {
                        this.SetFailReason("move operand unknown");
                        return null;
                    }
                    valueMap.put(moveInstr.GetDstValue(), value);
                } else if (instr instanceof CallInstr callInstr) {
                    if (callInstr.GetIrType().IsVoidType()) {
                        IrFunction callee = this.ResolveCallee(callInstr.GetTargetFunction());
                        if (callee == null || !this.IsPureFunction(callee, new HashSet<>())) {
                            this.SetFailReason("void call not pure");
                            return null;
                        }
                        continue;
                    }
                    IrFunction callee = this.ResolveCallee(callInstr.GetTargetFunction());
                    if (callee == null) {
                        this.SetFailReason("callee not found");
                        return null;
                    }
                    ArrayList<Integer> callArgs = this.GetConstArgsWithMap(
                        callInstr.GetParamList(), valueMap);
                    if (callArgs == null) {
                        this.SetFailReason("call args unknown");
                        return null;
                    }
                    Integer result = this.EvalFunction(callee, callArgs, stack);
                    if (result == null) {
                        return null;
                    }
                    valueMap.put(callInstr, result);
                } else if (instr instanceof BranchInstr branchInstr) {
                    Integer cond = this.GetConstValue(branchInstr.GetCond(), valueMap);
                    if (cond == null) {
                        this.SetFailReason("branch cond unknown");
                        return null;
                    }
                    prevBlock = currentBlock;
                    currentBlock = cond != 0 ?
                        branchInstr.GetTrueBlock() : branchInstr.GetFalseBlock();
                    jumped = true;
                    break;
                } else if (instr instanceof JumpInstr jumpInstr) {
                    if (jumpInstr.GetUseValueList().isEmpty()) {
                        this.SetFailReason("jump target missing");
                        return null;
                    }
                    prevBlock = currentBlock;
                    currentBlock = jumpInstr.GetTargetBlock();
                    jumped = true;
                    break;
                } else if (instr instanceof ReturnInstr returnInstr) {
                    IrValue returnValue = returnInstr.GetReturnValue();
                    if (returnValue == null) {
                        this.SetFailReason("void return");
                        return null;
                    }
                    return this.GetConstValue(returnValue, valueMap);
                } else if (instr instanceof LoadInstr || instr instanceof StoreInstr ||
                    instr instanceof IoInstr) {
                    this.SetFailReason("memory or io");
                    return null;
                } else {
                    this.SetFailReason("unsupported instr " + instr.getClass().getSimpleName());
                    return null;
                }
            }
            if (!pendingPhi.isEmpty()) {
                for (Map.Entry<PhiInstr, Integer> entry : pendingPhi.entrySet()) {
                    valueMap.put(entry.getKey(), entry.getValue());
                }
                pendingPhi.clear();
            }
            if (!jumped) {
                this.SetFailReason("fell through block");
                return null;
            }
        }
    }

    private Integer GetConstValue(IrValue value, Map<IrValue, Integer> valueMap) {
        if (value instanceof IrConstantInt irConstantInt) {
            return irConstantInt.GetValue();
        }
        if (valueMap == null) {
            return null;
        }
        return valueMap.get(value);
    }

    private ArrayList<Integer> GetConstArgsWithMap(ArrayList<IrValue> params,
                                                   Map<IrValue, Integer> valueMap) {
        ArrayList<Integer> constArgs = new ArrayList<>();
        for (IrValue param : params) {
            Integer value = this.GetConstValue(param, valueMap);
            if (value == null) {
                return null;
            }
            constArgs.add(value);
        }
        return constArgs;
    }

    private Integer EvalAlu(AluInstr aluInstr, int valueL, int valueR) {
        return switch (aluInstr.GetAluOp()) {
            case ADD -> valueL + valueR;
            case SUB -> valueL - valueR;
            case AND -> valueL & valueR;
            case OR -> valueL | valueR;
            case MUL -> valueL * valueR;
            case SDIV -> valueR == 0 ? null : valueL / valueR;
            case SREM -> valueR == 0 ? null : valueL % valueR;
        };
    }

    private int EvalCompare(CompareInstr compareInstr, int valueL, int valueR) {
        return switch (compareInstr.GetCompareOp()) {
            case EQ -> valueL == valueR ? 1 : 0;
            case NE -> valueL != valueR ? 1 : 0;
            case SGT -> valueL > valueR ? 1 : 0;
            case SGE -> valueL >= valueR ? 1 : 0;
            case SLT -> valueL < valueR ? 1 : 0;
            case SLE -> valueL <= valueR ? 1 : 0;
        };
    }

    private Integer EvalPhi(PhiInstr phiInstr, IrBasicBlock prevBlock,
                            Map<IrValue, Integer> valueMap) {
        if (prevBlock == null) {
            this.SetFailReason("phi prev block null");
            return null;
        }
        int index = phiInstr.GetBeforeBlockList().indexOf(prevBlock);
        if (index < 0) {
            this.SetFailReason("phi missing predecessor");
            return null;
        }
        IrValue incoming = phiInstr.GetUseValueList().get(index);
        if (incoming == null) {
            this.SetFailReason("phi incoming null");
            return null;
        }
        return this.GetConstValue(incoming, valueMap);
    }

    private boolean IsPureFunction(IrFunction irFunction, HashSet<IrFunction> visiting) {
        if (pureCache.containsKey(irFunction)) {
            return pureCache.get(irFunction);
        }
        if (visiting.contains(irFunction)) {
            return true;
        }
        visiting.add(irFunction);
        boolean pure = true;
        for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
            for (Instr instr : irBasicBlock.GetInstrList()) {
                if (instr instanceof IoInstr || instr instanceof StoreInstr ||
                    instr instanceof LoadInstr) {
                    pure = false;
                    break;
                }
                if (instr instanceof CallInstr callInstr) {
                    IrFunction callee = this.ResolveCallee(callInstr.GetTargetFunction());
                    if (callee == null || !this.IsPureFunction(callee, visiting)) {
                        pure = false;
                        break;
                    }
                }
            }
            if (!pure) {
                break;
            }
        }
        visiting.remove(irFunction);
        pureCache.put(irFunction, pure);
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

    private static class EvalKey {
        private final IrFunction function;
        private final ArrayList<Integer> args;

        private EvalKey(IrFunction function, ArrayList<Integer> args) {
            this.function = function;
            this.args = new ArrayList<>(args);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof EvalKey other)) {
                return false;
            }
            return this.function == other.function && this.args.equals(other.args);
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(this.function), this.args);
        }
    }
}
