package optimize;

import midend.llvm.IrBuilder;
import midend.llvm.constant.IrConstantInt;
import midend.llvm.instr.AluInstr;
import midend.llvm.instr.Instr;
import midend.llvm.use.IrUse;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.Iterator;

public class StrengthReduce extends Optimizer {
    private static final int COST_MUL = 5;

    @Override
    public void Optimize() {
        for (IrFunction function : irModule.GetFunctions()) {
            ReduceInFunction(function);
        }
    }

    private void ReduceInFunction(IrFunction function) {
        IrBuilder.SetCurrentFunction(function);
        for (IrBasicBlock block : function.GetBasicBlocks()) {
            ReduceInBlock(block);
        }
    }

    private void ReduceInBlock(IrBasicBlock block) {
        ArrayList<Instr> instrs = block.GetInstrList();
        for (int i = 0; i < instrs.size(); i++) {
            Instr instr = instrs.get(i);
            if (!(instr instanceof AluInstr baseInstr)) {
                continue;
            }
            if (TryReduceMulByConst(baseInstr, block, instrs)) {
                i--;
                continue;
            }
            LinearInfo baseInfo = GetLinearInfo(baseInstr);
            if (baseInfo == null) {
                continue;
            }
            if (baseInstr.GetUseList().size() != 1) {
                continue;
            }
            IrUse use = baseInstr.GetUseList().get(0);
            if (!(use.GetUser() instanceof AluInstr userInstr)) {
                continue;
            }
            LinearInfo userInfo = GetLinearInfo(userInstr);
            if (userInfo == null || userInfo.base != baseInstr) {
                continue;
            }

            int newOffset = baseInfo.offset + userInfo.offset;
            if (newOffset == 0) {
                userInstr.ModifyAllUsersToNewValue(baseInfo.base);
                RemoveInstr(userInstr);
                baseInstr.RemoveAllValueUse();
                instrs.remove(baseInstr);
                i--;
                continue;
            }

            int newConstValue = baseInstr.GetAluOp() == AluInstr.AluType.ADD ?
                newOffset : -newOffset;
            ReplaceConstOperand(baseInstr, baseInfo.constOperand, newConstValue);
            userInstr.ModifyAllUsersToNewValue(baseInstr);
            RemoveInstr(userInstr);
        }
    }

    private void RemoveInstr(Instr instr) {
        IrBasicBlock block = instr.GetInBasicBlock();
        if (block == null) {
            return;
        }
        instr.RemoveAllValueUse();
        Iterator<Instr> iterator = block.GetInstrList().iterator();
        while (iterator.hasNext()) {
            if (iterator.next() == instr) {
                iterator.remove();
                return;
            }
        }
    }

    private void ReplaceConstOperand(AluInstr instr, IrConstantInt oldConst, int newValue) {
        IrConstantInt newConst = new IrConstantInt(newValue);
        instr.ModifyValue(oldConst, newConst);
        oldConst.DeleteUser(instr);
        newConst.AddUse(new IrUse(instr, newConst));
    }

    private boolean TryReduceMulByConst(AluInstr instr, IrBasicBlock block,
                                        ArrayList<Instr> instrs) {
        if (instr.GetAluOp() != AluInstr.AluType.MUL) {
            return false;
        }
        IrValue left = instr.GetValueL();
        IrValue right = instr.GetValueR();
        IrValue base;
        IrConstantInt constant;
        if (left instanceof IrConstantInt c && !(right instanceof IrConstantInt)) {
            constant = c;
            base = right;
        } else if (right instanceof IrConstantInt c && !(left instanceof IrConstantInt)) {
            constant = c;
            base = left;
        } else {
            return false;
        }

        int value = constant.GetValue();
        if (value == 0 || value == 1) {
            return false;
        }
        if (value == Integer.MIN_VALUE) {
            return false;
        }

        int abs = Math.abs(value);
        int addCount = GetAddChainCost(abs);
        int extra = value < 0 ? 1 : 0;
        int newCost = addCount + extra;
        int mulCost = GetMulCost(value);
        if (newCost >= mulCost) {
            return false;
        }

        IrBuilder.SetCurrentBasicBlock(block);
        int insertIndex = instrs.indexOf(instr);
        IrValue current = base;
        int highestBit = 31 - Integer.numberOfLeadingZeros(abs);
        for (int bit = highestBit - 1; bit >= 0; bit--) {
            AluInstr doubled = new AluInstr("+", current, current);
            InsertInstrAt(block, instrs, doubled, insertIndex++);
            current = doubled;
            if (((abs >> bit) & 1) != 0) {
                AluInstr added = new AluInstr("+", current, base);
                InsertInstrAt(block, instrs, added, insertIndex++);
                current = added;
            }
        }

        if (value < 0) {
            AluInstr neg = new AluInstr("-", new IrConstantInt(0), current);
            InsertInstrAt(block, instrs, neg, insertIndex++);
            current = neg;
        }

        instr.ModifyAllUsersToNewValue(current);
        instr.RemoveAllValueUse();
        instrs.remove(instr);
        return true;
    }

    private void InsertInstrAt(IrBasicBlock block, ArrayList<Instr> instrs,
                               Instr instr, int index) {
        instrs.remove(instr);
        instrs.add(index, instr);
        instr.SetInBasicBlock(block);
    }

    private int GetAddChainCost(int absValue) {
        if (absValue <= 1) {
            return 0;
        }
        int bitLen = 32 - Integer.numberOfLeadingZeros(absValue);
        int popCount = Integer.bitCount(absValue);
        return bitLen + popCount - 2;
    }

    private int GetMulCost(int value) {
        int abs = Math.abs(value);
        if (abs == 0 || abs == 1) {
            return 1;
        }
        if (value > 0 && IsPowerOfTwo(abs)) {
            return 1;
        }
        return COST_MUL;
    }

    private boolean IsPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    private LinearInfo GetLinearInfo(AluInstr instr) {
        IrValue left = instr.GetValueL();
        IrValue right = instr.GetValueR();
        if (instr.GetAluOp() == AluInstr.AluType.ADD) {
            if (left instanceof IrConstantInt c && !(right instanceof IrConstantInt)) {
                return new LinearInfo(right, c.GetValue(), c);
            }
            if (right instanceof IrConstantInt c && !(left instanceof IrConstantInt)) {
                return new LinearInfo(left, c.GetValue(), c);
            }
        } else if (instr.GetAluOp() == AluInstr.AluType.SUB) {
            if (right instanceof IrConstantInt c && !(left instanceof IrConstantInt)) {
                return new LinearInfo(left, -c.GetValue(), c);
            }
        }
        return null;
    }

    private static class LinearInfo {
        private final IrValue base;
        private final int offset;
        private final IrConstantInt constOperand;

        private LinearInfo(IrValue base, int offset, IrConstantInt constOperand) {
            this.base = base;
            this.offset = offset;
            this.constOperand = constOperand;
        }
    }
}
