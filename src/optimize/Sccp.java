package optimize;

import midend.llvm.constant.IrConstantInt;
import midend.llvm.instr.AluInstr;
import midend.llvm.instr.BranchInstr;
import midend.llvm.instr.CompareInstr;
import midend.llvm.instr.ExtendInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.JumpInstr;
import midend.llvm.instr.TruncInstr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.type.IrType;
import midend.llvm.use.IrUse;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrParameter;
import midend.llvm.value.IrValue;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

public class Sccp extends Optimizer {
    private final HashMap<IrValue, LatticeValue> valueMap = new HashMap<>();
    private final HashSet<IrBasicBlock> executableBlocks = new HashSet<>();
    private final ArrayDeque<IrBasicBlock> blockWorklist = new ArrayDeque<>();
    private final ArrayDeque<Instr> instrWorklist = new ArrayDeque<>();

    @Override
    public void Optimize() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            RunOnFunction(irFunction);
        }
    }

    private void RunOnFunction(IrFunction irFunction) {
        valueMap.clear();
        executableBlocks.clear();
        blockWorklist.clear();
        instrWorklist.clear();

        for (IrParameter parameter : irFunction.GetParameterList()) {
            valueMap.put(parameter, LatticeValue.Overdefined());
        }
        for (IrBasicBlock block : irFunction.GetBasicBlocks()) {
            for (Instr instr : block.GetInstrList()) {
                if (instr.DefValue()) {
                    valueMap.put(instr, LatticeValue.Undefined());
                }
            }
        }

        if (!irFunction.GetBasicBlocks().isEmpty()) {
            MarkExecutable(irFunction.GetBasicBlocks().get(0));
        }

        while (!blockWorklist.isEmpty() || !instrWorklist.isEmpty()) {
            if (!blockWorklist.isEmpty()) {
                IrBasicBlock block = blockWorklist.poll();
                for (Instr instr : block.GetInstrList()) {
                    instrWorklist.add(instr);
                }
                continue;
            }

            Instr instr = instrWorklist.poll();
            if (instr == null) {
                continue;
            }
            IrBasicBlock block = instr.GetInBasicBlock();
            if (block == null || !executableBlocks.contains(block)) {
                continue;
            }

            if (instr instanceof BranchInstr branchInstr) {
                if (branchInstr.GetUseValueList().size() < 3) {
                    continue;
                }
                HandleBranch(branchInstr);
                continue;
            }
            if (instr instanceof JumpInstr jumpInstr) {
                if (jumpInstr.GetUseValueList().isEmpty()) {
                    continue;
                }
                MarkExecutable(jumpInstr.GetTargetBlock());
                continue;
            }
            if (!instr.DefValue()) {
                continue;
            }

            LatticeValue newValue = EvalInstr(instr);
            if (UpdateValue(instr, newValue)) {
                EnqueueUsers(instr);
            }
        }

        RewriteConstants(irFunction);
    }

    private void MarkExecutable(IrBasicBlock block) {
        if (block == null) {
            return;
        }
        boolean isNew = executableBlocks.add(block);
        if (isNew) {
            blockWorklist.add(block);
        }
        EnqueuePhi(block);
    }

    private void EnqueuePhi(IrBasicBlock block) {
        for (Instr instr : block.GetInstrList()) {
            if (instr instanceof PhiInstr) {
                instrWorklist.add(instr);
            } else {
                break;
            }
        }
    }

    private void HandleBranch(BranchInstr branchInstr) {
        LatticeValue cond = GetValue(branchInstr.GetCond());
        if (cond.state == LatticeState.CONST) {
            if (cond.constValue != 0) {
                MarkExecutable(branchInstr.GetTrueBlock());
            } else {
                MarkExecutable(branchInstr.GetFalseBlock());
            }
        } else {
            MarkExecutable(branchInstr.GetTrueBlock());
            MarkExecutable(branchInstr.GetFalseBlock());
        }
    }

    private void EnqueueUsers(IrValue value) {
        for (IrUse use : value.GetUseList()) {
            if (use.GetUser() instanceof Instr instr) {
                instrWorklist.add(instr);
            }
        }
    }

    private boolean UpdateValue(IrValue value, LatticeValue newValue) {
        LatticeValue oldValue = valueMap.get(value);
        if (oldValue == null) {
            valueMap.put(value, newValue);
            return true;
        }
        LatticeValue merged = MergeValue(oldValue, newValue);
        if (!merged.equals(oldValue)) {
            valueMap.put(value, merged);
            return true;
        }
        return false;
    }

    private LatticeValue MergeValue(LatticeValue oldValue, LatticeValue newValue) {
        if (oldValue.state == LatticeState.OVERDEFINED ||
            newValue.state == LatticeState.OVERDEFINED) {
            return LatticeValue.Overdefined();
        }
        if (oldValue.state == LatticeState.UNDEF) {
            return newValue;
        }
        if (newValue.state == LatticeState.UNDEF) {
            return oldValue;
        }
        if (oldValue.constValue.equals(newValue.constValue)) {
            return oldValue;
        }
        return LatticeValue.Overdefined();
    }

    private LatticeValue GetValue(IrValue value) {
        if (value instanceof IrConstantInt irConstantInt) {
            return LatticeValue.Constant(irConstantInt.GetValue());
        }
        LatticeValue latticeValue = valueMap.get(value);
        return latticeValue == null ? LatticeValue.Overdefined() : latticeValue;
    }

    private LatticeValue EvalInstr(Instr instr) {
        if (instr instanceof PhiInstr phiInstr) {
            return EvalPhi(phiInstr);
        }
        if (instr instanceof AluInstr aluInstr) {
            return EvalAlu(aluInstr);
        }
        if (instr instanceof CompareInstr compareInstr) {
            return EvalCompare(compareInstr);
        }
        if (instr instanceof ExtendInstr extendInstr) {
            return EvalExtend(extendInstr);
        }
        if (instr instanceof TruncInstr truncInstr) {
            return EvalTrunc(truncInstr);
        }
        return LatticeValue.Overdefined();
    }

    private LatticeValue EvalPhi(PhiInstr phiInstr) {
        LatticeValue result = LatticeValue.Undefined();
        for (int i = 0; i < phiInstr.GetBeforeBlockList().size(); i++) {
            IrBasicBlock pred = phiInstr.GetBeforeBlockList().get(i);
            if (!executableBlocks.contains(pred)) {
                continue;
            }
            IrValue incoming = phiInstr.GetUseValueList().get(i);
            if (incoming == null) {
                continue;
            }
            result = MergeValue(result, GetValue(incoming));
            if (result.state == LatticeState.OVERDEFINED) {
                return result;
            }
        }
        return result;
    }

    private LatticeValue EvalAlu(AluInstr aluInstr) {
        if (aluInstr.GetUseValueList().size() < 2) {
            return LatticeValue.Overdefined();
        }
        LatticeValue left = GetValue(aluInstr.GetValueL());
        LatticeValue right = GetValue(aluInstr.GetValueR());
        if (left.state == LatticeState.OVERDEFINED ||
            right.state == LatticeState.OVERDEFINED) {
            return LatticeValue.Overdefined();
        }
        if (left.state == LatticeState.UNDEF || right.state == LatticeState.UNDEF) {
            return LatticeValue.Undefined();
        }
        int valueL = left.constValue;
        int valueR = right.constValue;
        return switch (aluInstr.GetAluOp()) {
            case ADD -> LatticeValue.Constant(valueL + valueR);
            case SUB -> LatticeValue.Constant(valueL - valueR);
            case AND -> LatticeValue.Constant(valueL & valueR);
            case OR -> LatticeValue.Constant(valueL | valueR);
            case MUL -> LatticeValue.Constant(valueL * valueR);
            case SDIV -> valueR == 0 ? LatticeValue.Overdefined() :
                LatticeValue.Constant(valueL / valueR);
            case SREM -> valueR == 0 ? LatticeValue.Overdefined() :
                LatticeValue.Constant(valueL % valueR);
        };
    }

    private LatticeValue EvalCompare(CompareInstr compareInstr) {
        if (compareInstr.GetUseValueList().size() < 2) {
            return LatticeValue.Overdefined();
        }
        LatticeValue left = GetValue(compareInstr.GetValueL());
        LatticeValue right = GetValue(compareInstr.GetValueR());
        if (left.state == LatticeState.OVERDEFINED ||
            right.state == LatticeState.OVERDEFINED) {
            return LatticeValue.Overdefined();
        }
        if (left.state == LatticeState.UNDEF || right.state == LatticeState.UNDEF) {
            return LatticeValue.Undefined();
        }
        int valueL = left.constValue;
        int valueR = right.constValue;
        int result = switch (compareInstr.GetCompareOp()) {
            case EQ -> valueL == valueR ? 1 : 0;
            case NE -> valueL != valueR ? 1 : 0;
            case SGT -> valueL > valueR ? 1 : 0;
            case SGE -> valueL >= valueR ? 1 : 0;
            case SLT -> valueL < valueR ? 1 : 0;
            case SLE -> valueL <= valueR ? 1 : 0;
        };
        return LatticeValue.Constant(result);
    }

    private LatticeValue EvalExtend(ExtendInstr extendInstr) {
        if (extendInstr.GetUseValueList().isEmpty()) {
            return LatticeValue.Overdefined();
        }
        LatticeValue value = GetValue(extendInstr.GetOriginValue());
        if (value.state != LatticeState.CONST) {
            return value.state == LatticeState.OVERDEFINED ?
                LatticeValue.Overdefined() : LatticeValue.Undefined();
        }
        int result = ApplyZext(value.constValue, extendInstr.GetOriginType());
        return LatticeValue.Constant(result);
    }

    private LatticeValue EvalTrunc(TruncInstr truncInstr) {
        if (truncInstr.GetUseValueList().isEmpty()) {
            return LatticeValue.Overdefined();
        }
        LatticeValue value = GetValue(truncInstr.GetOriginValue());
        if (value.state != LatticeState.CONST) {
            return value.state == LatticeState.OVERDEFINED ?
                LatticeValue.Overdefined() : LatticeValue.Undefined();
        }
        int result = ApplyTrunc(value.constValue, truncInstr.GetTargetType());
        return LatticeValue.Constant(result);
    }

    private int ApplyZext(int value, IrType originType) {
        if (originType.IsInt1Type()) {
            return value & 0x1;
        }
        if (originType.IsInt8Type()) {
            return value & 0xff;
        }
        return value;
    }

    private int ApplyTrunc(int value, IrType targetType) {
        if (targetType.IsInt1Type()) {
            return value & 0x1;
        }
        if (targetType.IsInt8Type()) {
            return value & 0xff;
        }
        return value;
    }

    private void RewriteConstants(IrFunction irFunction) {
        for (IrBasicBlock block : irFunction.GetBasicBlocks()) {
            Iterator<Instr> iterator = block.GetInstrList().iterator();
            while (iterator.hasNext()) {
                Instr instr = iterator.next();
                if (!instr.DefValue()) {
                    continue;
                }
                LatticeValue latticeValue = valueMap.get(instr);
                if (latticeValue == null || latticeValue.state != LatticeState.CONST) {
                    continue;
                }
                IrConstantInt constant = new IrConstantInt(latticeValue.constValue);
                instr.ModifyAllUsersToNewValue(constant);
                instr.RemoveAllValueUse();
                iterator.remove();
            }
        }
    }

    private enum LatticeState {
        UNDEF,
        CONST,
        OVERDEFINED
    }

    private static class LatticeValue {
        private final LatticeState state;
        private final Integer constValue;

        private LatticeValue(LatticeState state, Integer constValue) {
            this.state = state;
            this.constValue = constValue;
        }

        private static LatticeValue Undefined() {
            return new LatticeValue(LatticeState.UNDEF, null);
        }

        private static LatticeValue Constant(int value) {
            return new LatticeValue(LatticeState.CONST, value);
        }

        private static LatticeValue Overdefined() {
            return new LatticeValue(LatticeState.OVERDEFINED, null);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof LatticeValue other)) {
                return false;
            }
            if (this.state != other.state) {
                return false;
            }
            if (this.state != LatticeState.CONST) {
                return true;
            }
            return this.constValue.equals(other.constValue);
        }

        @Override
        public int hashCode() {
            return this.state == LatticeState.CONST ? this.constValue.hashCode() :
                this.state.hashCode();
        }
    }
}
