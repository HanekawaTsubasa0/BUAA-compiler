package optimize;

import midend.llvm.IrBuilder;
import midend.llvm.constant.IrConstantInt;
import midend.llvm.instr.AluInstr;
import midend.llvm.instr.BranchInstr;
import midend.llvm.instr.CallInstr;
import midend.llvm.instr.CompareInstr;
import midend.llvm.instr.ExtendInstr;
import midend.llvm.instr.GepInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.JumpInstr;
import midend.llvm.instr.LoadInstr;
import midend.llvm.instr.ReturnInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.instr.TruncInstr;
import midend.llvm.instr.io.GetCharInstr;
import midend.llvm.instr.io.GetIntInstr;
import midend.llvm.instr.io.IoInstr;
import midend.llvm.instr.io.PrintCharInstr;
import midend.llvm.instr.io.PrintIntInstr;
import midend.llvm.instr.io.PrintStrInstr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.use.IrUse;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

public class LoopUnroll extends Optimizer {
    private static final int MAX_UNROLL = 10000;
    private static final int MAX_UNROLL_INSTRS = 2000;

    @Override
    public void Optimize() {
        for (IrFunction function : irModule.GetFunctions()) {
            this.UnrollInFunction(function);
        }
    }

    private void UnrollInFunction(IrFunction function) {
        ArrayList<IrBasicBlock> blocks = new ArrayList<>(function.GetBasicBlocks());
        for (IrBasicBlock condBlock : blocks) {
            LoopInfo loopInfo = this.MatchLoop(condBlock);
            if (loopInfo == null) {
                continue;
            }
            this.TryUnroll(function, loopInfo);
        }
    }

    private LoopInfo MatchLoop(IrBasicBlock condBlock) {
        Instr lastInstr = condBlock.GetLastInstr();
        if (!(lastInstr instanceof BranchInstr branchInstr)) {
            return null;
        }
        if (branchInstr.GetUseValueList().size() < 3) {
            return null;
        }
        IrBasicBlock bodyBlock = branchInstr.GetTrueBlock();
        IrBasicBlock followBlock = branchInstr.GetFalseBlock();

        Instr bodyLast = bodyBlock.GetLastInstr();
        if (!(bodyLast instanceof JumpInstr bodyJump)) {
            return null;
        }
        if (bodyJump.GetUseValueList().isEmpty()) {
            return null;
        }
        IrBasicBlock stepBlock;
        if (bodyJump.GetTargetBlock() == condBlock) {
            stepBlock = bodyBlock;
        } else {
            stepBlock = bodyJump.GetTargetBlock();
            Instr stepLast = stepBlock.GetLastInstr();
            if (!(stepLast instanceof JumpInstr stepJump)) {
                return null;
            }
            if (stepJump.GetUseValueList().isEmpty()) {
                return null;
            }
            if (stepJump.GetTargetBlock() != condBlock) {
                return null;
            }
        }

        if (!HasSinglePred(bodyBlock, condBlock)) {
            return null;
        }
        if (stepBlock != bodyBlock && !HasSinglePred(stepBlock, bodyBlock)) {
            return null;
        }

        ArrayList<IrBasicBlock> preds = condBlock.GetBeforeBlocks();
        if (preds.size() != 2 || !preds.contains(stepBlock)) {
            return null;
        }
        IrBasicBlock preheader = preds.get(0) == stepBlock ? preds.get(1) : preds.get(0);
        if (!(preheader.GetLastInstr() instanceof JumpInstr preJump)) {
            return null;
        }
        if (preJump.GetUseValueList().isEmpty()) {
            return null;
        }
        if (preJump.GetTargetBlock() != condBlock) {
            return null;
        }

        ArrayList<PhiInstr> phiList = this.GetPhiList(condBlock);
        if (phiList.size() != 1) {
            return null;
        }
        PhiInstr inductionPhi = phiList.get(0);

        InductionInfo inductionInfo = this.MatchInduction(inductionPhi, preheader, stepBlock);
        if (inductionInfo == null) {
            return null;
        }

        CompareInfo compareInfo = this.MatchCompare(branchInstr.GetCond(), inductionPhi);
        if (compareInfo == null) {
            return null;
        }
        if (!this.IsSimpleCondBlock(condBlock, branchInstr)) {
            return null;
        }
        Integer tripCount = this.ComputeTripCount(inductionInfo, compareInfo);
        if (tripCount == null || tripCount < 0 || tripCount > MAX_UNROLL) {
            return null;
        }

        if (!this.IsSimpleBody(bodyBlock) || !this.IsSimpleStep(stepBlock)) {
            return null;
        }

        HashSet<IrBasicBlock> loopBlocks = new HashSet<>();
        loopBlocks.add(condBlock);
        loopBlocks.add(bodyBlock);
        loopBlocks.add(stepBlock);
        if (!this.NoOutsideDefUse(loopBlocks, bodyBlock, stepBlock)) {
            return null;
        }

        return new LoopInfo(condBlock, bodyBlock, stepBlock, followBlock,
            preheader, inductionPhi, inductionInfo, compareInfo, tripCount);
    }

    private boolean HasSinglePred(IrBasicBlock block, IrBasicBlock pred) {
        ArrayList<IrBasicBlock> preds = block.GetBeforeBlocks();
        return preds.size() == 1 && preds.get(0) == pred;
    }

    private boolean IsSimpleCondBlock(IrBasicBlock condBlock, BranchInstr branchInstr) {
        for (Instr instr : condBlock.GetInstrList()) {
            if (instr == branchInstr) {
                continue;
            }
            if (instr instanceof PhiInstr ||
                instr instanceof CompareInstr ||
                instr instanceof ExtendInstr ||
                instr instanceof TruncInstr) {
                continue;
            }
            return false;
        }
        return true;
    }

    private ArrayList<PhiInstr> GetPhiList(IrBasicBlock block) {
        ArrayList<PhiInstr> phiList = new ArrayList<>();
        for (Instr instr : block.GetInstrList()) {
            if (instr instanceof PhiInstr phiInstr) {
                phiList.add(phiInstr);
            } else {
                break;
            }
        }
        return phiList;
    }

    private InductionInfo MatchInduction(PhiInstr phiInstr, IrBasicBlock preheader,
                                         IrBasicBlock stepBlock) {
        int preIndex = phiInstr.GetBeforeBlockList().indexOf(preheader);
        int stepIndex = phiInstr.GetBeforeBlockList().indexOf(stepBlock);
        if (preIndex < 0 || stepIndex < 0) {
            return null;
        }
        IrValue initValue = phiInstr.GetUseValueList().get(preIndex);
        IrValue stepValue = phiInstr.GetUseValueList().get(stepIndex);
        if (!(initValue instanceof IrConstantInt initConst)) {
            return null;
        }
        if (!(stepValue instanceof AluInstr aluInstr)) {
            return null;
        }
        if (aluInstr.GetInBasicBlock() != stepBlock) {
            return null;
        }
        Integer step = this.GetStepFromAlu(aluInstr, phiInstr);
        if (step == null || step == 0) {
            return null;
        }
        return new InductionInfo(initConst.GetValue(), step, stepValue);
    }

    private Integer GetStepFromAlu(AluInstr aluInstr, PhiInstr phiInstr) {
        IrValue left = aluInstr.GetValueL();
        IrValue right = aluInstr.GetValueR();
        if (aluInstr.GetAluOp() == AluInstr.AluType.ADD) {
            if (left == phiInstr && right instanceof IrConstantInt c) {
                return c.GetValue();
            }
            if (right == phiInstr && left instanceof IrConstantInt c) {
                return c.GetValue();
            }
        } else if (aluInstr.GetAluOp() == AluInstr.AluType.SUB) {
            if (left == phiInstr && right instanceof IrConstantInt c) {
                return -c.GetValue();
            }
        }
        return null;
    }

    private CompareInfo MatchCompare(IrValue cond, PhiInstr phiInstr) {
        CompareInstr baseCompare = this.GetBaseCompare(cond);
        if (baseCompare == null) {
            return null;
        }
        IrValue left = baseCompare.GetValueL();
        IrValue right = baseCompare.GetValueR();
        CompareInstr.CompareOp op = baseCompare.GetCompareOp();

        if (left == phiInstr && right instanceof IrConstantInt rightConst) {
            return new CompareInfo(op, rightConst.GetValue());
        }
        if (right == phiInstr && left instanceof IrConstantInt leftConst) {
            CompareInstr.CompareOp swapped = this.SwapCompare(op);
            return swapped == null ? null : new CompareInfo(swapped, leftConst.GetValue());
        }
        return null;
    }

    private CompareInstr GetBaseCompare(IrValue cond) {
        if (cond instanceof CompareInstr compareInstr) {
            if (compareInstr.GetCompareOp() == CompareInstr.CompareOp.NE &&
                compareInstr.GetValueR() instanceof IrConstantInt irConstantInt &&
                irConstantInt.GetValue() == 0) {
                IrValue left = compareInstr.GetValueL();
                if (left instanceof ExtendInstr extendInstr &&
                    extendInstr.GetOriginValue() instanceof CompareInstr inner) {
                    return inner;
                }
                if (left instanceof TruncInstr truncInstr &&
                    truncInstr.GetOriginValue() instanceof CompareInstr inner) {
                    return inner;
                }
            }
            return compareInstr;
        }
        return null;
    }

    private CompareInstr.CompareOp SwapCompare(CompareInstr.CompareOp op) {
        return switch (op) {
            case SLT -> CompareInstr.CompareOp.SGT;
            case SLE -> CompareInstr.CompareOp.SGE;
            case SGT -> CompareInstr.CompareOp.SLT;
            case SGE -> CompareInstr.CompareOp.SLE;
            case EQ -> CompareInstr.CompareOp.EQ;
            case NE -> CompareInstr.CompareOp.NE;
        };
    }

    private Integer ComputeTripCount(InductionInfo induction, CompareInfo compare) {
        int init = induction.init;
        int step = induction.step;
        int bound = compare.bound;

        if (compare.op == CompareInstr.CompareOp.SLT) {
            if (step <= 0) {
                return null;
            }
            if (init >= bound) {
                return 0;
            }
            return (int) (((long) bound - init + step - 1) / step);
        } else if (compare.op == CompareInstr.CompareOp.SLE) {
            if (step <= 0) {
                return null;
            }
            if (init > bound) {
                return 0;
            }
            return (int) (((long) bound - init) / step + 1);
        } else if (compare.op == CompareInstr.CompareOp.SGT) {
            if (step >= 0) {
                return null;
            }
            if (init <= bound) {
                return 0;
            }
            int diff = init - bound;
            int negStep = -step;
            return (int) (((long) diff + negStep - 1) / negStep);
        } else if (compare.op == CompareInstr.CompareOp.SGE) {
            if (step >= 0) {
                return null;
            }
            if (init < bound) {
                return 0;
            }
            int diff = init - bound;
            int negStep = -step;
            return (int) (((long) diff) / negStep + 1);
        }
        return null;
    }

    private boolean IsSimpleBody(IrBasicBlock bodyBlock) {
        Instr lastInstr = bodyBlock.GetLastInstr();
        return lastInstr instanceof JumpInstr;
    }

    private boolean IsSimpleStep(IrBasicBlock stepBlock) {
        Instr lastInstr = stepBlock.GetLastInstr();
        return lastInstr instanceof JumpInstr;
    }

    private boolean NoOutsideDefUse(HashSet<IrBasicBlock> loopBlocks,
                                    IrBasicBlock bodyBlock, IrBasicBlock stepBlock) {
        for (IrBasicBlock block : new IrBasicBlock[]{bodyBlock, stepBlock}) {
            for (Instr instr : block.GetInstrList()) {
                if (instr == block.GetLastInstr()) {
                    continue;
                }
                if (instr instanceof JumpInstr || instr instanceof BranchInstr ||
                    instr instanceof ReturnInstr || instr instanceof PhiInstr) {
                    return false;
                }
                if (!this.IsSupportedInstr(instr)) {
                    return false;
                }
                if (!instr.DefValue()) {
                    continue;
                }
                for (IrUse use : new ArrayList<>(instr.GetUseList())) {
                    if (!(use.GetUser() instanceof Instr userInstr)) {
                        continue;
                    }
                    IrBasicBlock useBlock = userInstr.GetInBasicBlock();
                    if (useBlock != null && !loopBlocks.contains(useBlock)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean IsSupportedInstr(Instr instr) {
        return instr instanceof AluInstr ||
            instr instanceof CompareInstr ||
            instr instanceof ExtendInstr ||
            instr instanceof TruncInstr ||
            instr instanceof GepInstr ||
            instr instanceof LoadInstr ||
            instr instanceof StoreInstr ||
            instr instanceof CallInstr ||
            instr instanceof PrintIntInstr ||
            instr instanceof PrintCharInstr ||
            instr instanceof PrintStrInstr ||
            instr instanceof GetIntInstr ||
            instr instanceof GetCharInstr;
    }

    private void TryUnroll(IrFunction function, LoopInfo loop) {
        int tripCount = loop.tripCount;
        int finalValue = loop.induction.init + loop.induction.step * tripCount;

        if (tripCount == 0) {
            JumpInstr jumpToFollow = new JumpInstr(loop.followBlock, loop.preheader);
            loop.preheader.ReplaceLastInstr(jumpToFollow);
            this.ReplacePhiOutside(loop, finalValue);
            this.RemoveLoopBlocks(function, loop);
            return;
        }

        IrBasicBlock unrollBlock = IrBuilder.GetNewBasicBlockIr(function, loop.condBlock);
        IrBuilder.SetCurrentFunction(function);
        IrBuilder.SetCurrentBasicBlock(unrollBlock);

        ArrayList<Instr> bodyInstrs = new ArrayList<>(loop.bodyBlock.GetInstrList());
        bodyInstrs.remove(bodyInstrs.size() - 1);
        ArrayList<Instr> stepInstrs = new ArrayList<>();
        if (loop.stepBlock != loop.bodyBlock) {
            stepInstrs = new ArrayList<>(loop.stepBlock.GetInstrList());
            stepInstrs.remove(stepInstrs.size() - 1);
        }
        long estimatedInstrs = (long) tripCount * (bodyInstrs.size() +
            stepInstrs.size());
        if (estimatedInstrs > MAX_UNROLL_INSTRS) {
            return;
        }

        for (int iter = 0; iter < tripCount; iter++) {
            HashMap<IrValue, IrValue> valueMap = new HashMap<>();
            valueMap.put(loop.inductionPhi, new IrConstantInt(
                loop.induction.init + loop.induction.step * iter));
            this.CloneInstrList(bodyInstrs, valueMap);
            this.CloneInstrList(stepInstrs, valueMap);
        }

        unrollBlock.AddInstr(new JumpInstr(loop.followBlock, unrollBlock));

        JumpInstr jumpToUnroll = new JumpInstr(unrollBlock, loop.preheader);
        loop.preheader.ReplaceLastInstr(jumpToUnroll);

        this.ReplacePhiOutside(loop, finalValue);
        this.RemoveLoopBlocks(function, loop);
    }

    private void ReplacePhiOutside(LoopInfo loop, int finalValue) {
        IrConstantInt finalConst = new IrConstantInt(finalValue);
        ArrayList<IrUse> uses = new ArrayList<>(loop.inductionPhi.GetUseList());
        for (IrUse use : uses) {
            if (!(use.GetUser() instanceof Instr instr)) {
                continue;
            }
            IrBasicBlock block = instr.GetInBasicBlock();
            if (block != null &&
                (block == loop.condBlock || block == loop.bodyBlock || block == loop.stepBlock)) {
                continue;
            }
            instr.ModifyValue(loop.inductionPhi, finalConst);
            loop.inductionPhi.DeleteUser(instr);
            finalConst.AddUse(new IrUse(instr, finalConst));
        }
    }

    private void RemoveLoopBlocks(IrFunction function, LoopInfo loop) {
        this.RemoveBlock(function, loop.condBlock);
        this.RemoveBlock(function, loop.bodyBlock);
        if (loop.stepBlock != loop.bodyBlock) {
            this.RemoveBlock(function, loop.stepBlock);
        }
    }

    private void RemoveBlock(IrFunction function, IrBasicBlock block) {
        for (Instr instr : block.GetInstrList()) {
            instr.RemoveAllValueUse();
        }
        function.GetBasicBlocks().remove(block);
    }

    private void CloneInstrList(ArrayList<Instr> instrs, HashMap<IrValue, IrValue> map) {
        for (Instr instr : instrs) {
            Instr cloned = this.CloneInstr(instr, map);
            if (cloned == null) {
                return;
            }
            if (instr.DefValue()) {
                map.put(instr, cloned);
            }
        }
    }

    private IrValue MapValue(IrValue value, HashMap<IrValue, IrValue> map) {
        IrValue mapped = map.get(value);
        return mapped == null ? value : mapped;
    }

    private Instr CloneInstr(Instr instr, HashMap<IrValue, IrValue> map) {
        if (instr instanceof AluInstr aluInstr) {
            String op = this.AluToString(aluInstr.GetAluOp());
            return new AluInstr(op,
                this.MapValue(aluInstr.GetValueL(), map),
                this.MapValue(aluInstr.GetValueR(), map));
        }
        if (instr instanceof CompareInstr compareInstr) {
            String op = this.CompareToString(compareInstr.GetCompareOp());
            return new CompareInstr(op,
                this.MapValue(compareInstr.GetValueL(), map),
                this.MapValue(compareInstr.GetValueR(), map));
        }
        if (instr instanceof ExtendInstr extendInstr) {
            return new ExtendInstr(this.MapValue(extendInstr.GetOriginValue(), map),
                extendInstr.GetTargetType());
        }
        if (instr instanceof TruncInstr truncInstr) {
            return new TruncInstr(this.MapValue(truncInstr.GetOriginValue(), map),
                truncInstr.GetTargetType());
        }
        if (instr instanceof GepInstr gepInstr) {
            return new GepInstr(this.MapValue(gepInstr.GetPointer(), map),
                this.MapValue(gepInstr.GetOffset(), map));
        }
        if (instr instanceof LoadInstr loadInstr) {
            return new LoadInstr(this.MapValue(loadInstr.GetPointer(), map));
        }
        if (instr instanceof StoreInstr storeInstr) {
            return new StoreInstr(this.MapValue(storeInstr.GetValueValue(), map),
                this.MapValue(storeInstr.GetAddressValue(), map));
        }
        if (instr instanceof CallInstr callInstr) {
            ArrayList<IrValue> params = callInstr.GetParamList();
            ArrayList<IrValue> mappedParams = new ArrayList<>();
            for (IrValue param : params) {
                mappedParams.add(this.MapValue(param, map));
            }
            return new CallInstr(callInstr.GetTargetFunction(), mappedParams);
        }
        if (instr instanceof PrintIntInstr printIntInstr) {
            return new PrintIntInstr(this.MapValue(printIntInstr.GetPrintValue(), map));
        }
        if (instr instanceof PrintCharInstr printCharInstr) {
            return new PrintCharInstr(this.MapValue(printCharInstr.GetPrintValue(), map));
        }
        if (instr instanceof PrintStrInstr printStrInstr) {
            return new PrintStrInstr(printStrInstr.GetConstString());
        }
        if (instr instanceof GetIntInstr) {
            return new GetIntInstr();
        }
        if (instr instanceof GetCharInstr) {
            return new GetCharInstr();
        }
        if (instr instanceof IoInstr) {
            return null;
        }
        return null;
    }

    private String AluToString(AluInstr.AluType op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case SDIV -> "/";
            case SREM -> "%";
            case AND -> "&";
            case OR -> "|";
        };
    }

    private String CompareToString(CompareInstr.CompareOp op) {
        return switch (op) {
            case EQ -> "==";
            case NE -> "!=";
            case SGT -> ">";
            case SGE -> ">=";
            case SLT -> "<";
            case SLE -> "<=";
        };
    }

    private static class InductionInfo {
        private final int init;
        private final int step;
        private final IrValue stepValue;

        private InductionInfo(int init, int step, IrValue stepValue) {
            this.init = init;
            this.step = step;
            this.stepValue = stepValue;
        }
    }

    private static class CompareInfo {
        private final CompareInstr.CompareOp op;
        private final int bound;

        private CompareInfo(CompareInstr.CompareOp op, int bound) {
            this.op = op;
            this.bound = bound;
        }
    }

    private static class LoopInfo {
        private final IrBasicBlock condBlock;
        private final IrBasicBlock bodyBlock;
        private final IrBasicBlock stepBlock;
        private final IrBasicBlock followBlock;
        private final IrBasicBlock preheader;
        private final PhiInstr inductionPhi;
        private final InductionInfo induction;
        private final CompareInfo compare;
        private final int tripCount;

        private LoopInfo(IrBasicBlock condBlock, IrBasicBlock bodyBlock,
                         IrBasicBlock stepBlock, IrBasicBlock followBlock,
                         IrBasicBlock preheader, PhiInstr inductionPhi,
                         InductionInfo induction, CompareInfo compare,
                         int tripCount) {
            this.condBlock = condBlock;
            this.bodyBlock = bodyBlock;
            this.stepBlock = stepBlock;
            this.followBlock = followBlock;
            this.preheader = preheader;
            this.inductionPhi = inductionPhi;
            this.induction = induction;
            this.compare = compare;
            this.tripCount = tripCount;
        }
    }
}
