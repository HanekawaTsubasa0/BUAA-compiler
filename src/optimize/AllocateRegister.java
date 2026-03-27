package optimize;

import midend.llvm.value.IrFunction;

public class AllocateRegister extends Optimizer {
    @Override
    public void Optimize() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            RegisterAllocator allocator = new RegisterAllocator(irFunction);
            allocator.Allocate(irFunction.GetBasicBlocks().get(0));
        }
    }
}
