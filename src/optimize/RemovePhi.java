package optimize;

import backend.mips.Register;
import midend.llvm.constant.IrConstant;
import midend.llvm.instr.Instr;
import midend.llvm.instr.MoveInstr;
import midend.llvm.instr.phi.ParallelCopyInstr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

public class RemovePhi extends Optimizer {
    @Override
    public void Optimize() {
        this.ConvertPhiToParallelCopy();
        this.ConvertParallelCopyToMove();
    }

    private void ConvertPhiToParallelCopy() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            ArrayList<IrBasicBlock> blockList = new ArrayList<>(irFunction.GetBasicBlocks());
            for (IrBasicBlock irBasicBlock : blockList) {
                if (!(irBasicBlock.GetFirstInstr() instanceof PhiInstr)) {
                    continue;
                }

                HashMap<IrBasicBlock, ParallelCopyInstr> copyMap = new HashMap<>();
                for (IrBasicBlock beforeBlock : this.GetPhiBeforeBlocks(irBasicBlock)) {
                    ParallelCopyInstr copyInstr = beforeBlock.GetNextBlocks().size() == 1 ?
                        this.InsertCopyDirect(beforeBlock) :
                        this.InsertCopyToMiddle(beforeBlock, irBasicBlock);
                    copyMap.put(beforeBlock, copyInstr);
                }

                Iterator<Instr> iterator = irBasicBlock.GetInstrList().iterator();
                while (iterator.hasNext()) {
                    Instr instr = iterator.next();
                    if (instr instanceof PhiInstr phiInstr) {
                        ArrayList<IrValue> useValueList = phiInstr.GetUseValueList();
                        ArrayList<IrBasicBlock> phiBeforeBlocks =
                            phiInstr.GetBeforeBlockList();
                        for (int i = 0; i < useValueList.size(); i++) {
                            IrValue useValue = useValueList.get(i);
                            IrBasicBlock beforeBlock = phiBeforeBlocks.get(i);
                            ParallelCopyInstr copyInstr = copyMap.get(beforeBlock);
                            if (copyInstr != null) {
                                copyInstr.AddCopy(useValue, phiInstr);
                            }
                        }
                        iterator.remove();
                    }
                }
            }
        }
    }

    private ParallelCopyInstr InsertCopyDirect(IrBasicBlock beforeBlock) {
        ParallelCopyInstr copyInstr = new ParallelCopyInstr(beforeBlock);
        beforeBlock.AddInstrBeforeJump(copyInstr);
        return copyInstr;
    }

    private ParallelCopyInstr InsertCopyToMiddle(IrBasicBlock beforeBlock, IrBasicBlock nextBlock) {
        IrBasicBlock middleBlock = IrBasicBlock.AddMiddleBlock(beforeBlock, nextBlock);
        ParallelCopyInstr copyInstr = new ParallelCopyInstr(middleBlock);
        middleBlock.AddInstrBeforeJump(copyInstr);
        return copyInstr;
    }

    private ArrayList<IrBasicBlock> GetPhiBeforeBlocks(IrBasicBlock irBasicBlock) {
        HashSet<IrBasicBlock> blockSet = new HashSet<>();
        for (Instr instr : irBasicBlock.GetInstrList()) {
            if (instr instanceof PhiInstr phiInstr) {
                blockSet.addAll(phiInstr.GetBeforeBlockList());
            }
        }
        return new ArrayList<>(blockSet);
    }

    private void ConvertParallelCopyToMove() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                if (irBasicBlock.HaveParallelCopyInstr()) {
                    ParallelCopyInstr copyInstr = irBasicBlock.GetAndRemoveParallelCopyInstr();
                    this.ConvertCopyToMove(copyInstr, irBasicBlock);
                }
            }
        }
    }

    private void ConvertCopyToMove(ParallelCopyInstr copyInstr, IrBasicBlock irBasicBlock) {
        ArrayList<MoveInstr> moveList = this.ConvertCopy(copyInstr, irBasicBlock);
        moveList = this.ReorderMoveList(moveList);
        ArrayList<MoveInstr> circleList =
            this.CheckCircleConflict(irBasicBlock, moveList);
        ArrayList<MoveInstr> registerList = this.CheckRegisterConflict(moveList, irBasicBlock);
        moveList.addAll(0, registerList);
        moveList.addAll(circleList);
        moveList.forEach(irBasicBlock::AddInstrBeforeJump);
    }

    private ArrayList<MoveInstr> ConvertCopy(ParallelCopyInstr copyInstr,
                                             IrBasicBlock irBasicBlock) {
        ArrayList<IrValue> srcList = copyInstr.GetSrcList();
        ArrayList<IrValue> dstList = copyInstr.GetDstList();

        ArrayList<MoveInstr> moveList = new ArrayList<>();
        for (int i = 0; i < dstList.size(); i++) {
            moveList.add(new MoveInstr(srcList.get(i), dstList.get(i), irBasicBlock));
        }

        return moveList;
    }

    private ArrayList<MoveInstr> CheckCircleConflict(
        IrBasicBlock irBasicBlock, ArrayList<MoveInstr> moveList) {
        HashSet<IrValue> valueRecord = new HashSet<>();
        for (int i = 0; i < moveList.size(); i++) {
            IrValue dstValue = moveList.get(i).GetDstValue();

            if (!(dstValue instanceof IrConstant) && !valueRecord.contains(dstValue)) {
                if (this.HaveCircleConflict(moveList, i)) {
                    IrValue middleValue = new IrValue(dstValue.GetIrType(),
                        dstValue.GetIrName() + "_tmp");
                    MoveInstr saveMove = new MoveInstr(dstValue, middleValue, irBasicBlock);
                    moveList.add(0, saveMove);
                    for (MoveInstr moveInstr : moveList) {
                        if (moveInstr == saveMove) {
                            continue;
                        }
                        if (moveInstr.GetSrcValue().equals(dstValue)) {
                            moveInstr.SetSrcValue(middleValue);
                        }
                    }
                }
                valueRecord.add(dstValue);
            }
        }
        return new ArrayList<>();
    }

    private boolean HaveCircleConflict(ArrayList<MoveInstr> moveList, int index) {
        IrValue dstValue = moveList.get(index).GetDstValue();
        for (int i = index + 1; i < moveList.size(); i++) {
            if (moveList.get(i).GetSrcValue().equals(dstValue)) {
                return true;
            }
        }
        return false;
    }

    private ArrayList<MoveInstr> ReorderMoveList(ArrayList<MoveInstr> moveList) {
        ArrayList<MoveInstr> workList = new ArrayList<>(moveList);
        ArrayList<MoveInstr> result = new ArrayList<>();
        while (!workList.isEmpty()) {
            boolean progress = false;
            for (int i = 0; i < workList.size(); i++) {
                MoveInstr moveInstr = workList.get(i);
                IrValue dstValue = moveInstr.GetDstValue();
                boolean usedAsSrc = false;
                for (MoveInstr otherMove : workList) {
                    if (otherMove != moveInstr
                        && otherMove.GetSrcValue().equals(dstValue)) {
                        usedAsSrc = true;
                        break;
                    }
                }
                if (!usedAsSrc) {
                    result.add(moveInstr);
                    workList.remove(i);
                    progress = true;
                    break;
                }
            }
            if (!progress) {
                result.addAll(workList);
                break;
            }
        }
        return result;
    }

    private ArrayList<MoveInstr> CheckRegisterConflict(ArrayList<MoveInstr> moveList,
                                                       IrBasicBlock irBasicBlock) {
        ArrayList<MoveInstr> fixList = new ArrayList<>();
        HashSet<IrValue> valueRecord = new HashSet<>();
        for (int i = moveList.size() - 1; i >= 0; i--) {
            IrValue srcValue = moveList.get(i).GetSrcValue();
            if (!(srcValue instanceof IrConstant) && !valueRecord.contains(srcValue)) {
                if (this.HaveRegisterConflict(moveList, i, irBasicBlock)) {
                    IrValue middleValue = new IrValue(srcValue.GetIrType(),
                        srcValue.GetIrName() + "_tmp");
                    for (MoveInstr moveInstr : moveList) {
                        if (moveInstr.GetSrcValue() == srcValue) {
                            moveInstr.SetSrcValue(middleValue);
                        }
                    }
                    MoveInstr moveInstr = new MoveInstr(srcValue, middleValue, irBasicBlock);
                    fixList.add(moveInstr);
                }
                valueRecord.add(srcValue);
            }
        }
        return fixList;
    }

    private boolean HaveRegisterConflict(ArrayList<MoveInstr> moveList, int index,
                                         IrBasicBlock irBasicBlock) {
        HashMap<IrValue, Register> registerMap = irBasicBlock.GetIrFunction().GetValueRegisterMap();
        IrValue srcValue = moveList.get(index).GetSrcValue();
        Register srcRegister = registerMap.get(srcValue);
        if (srcRegister == null) {
            return false;
        }

        if (srcRegister != null) {
            for (int i = 0; i < index; i++) {
                IrValue dstValue = moveList.get(i).GetDstValue();
                Register dstRegister = registerMap.get(dstValue);
                if (dstRegister != null && dstRegister.equals(srcRegister)) {
                    return true;
                }
            }
        }
        return false;
    }
}
