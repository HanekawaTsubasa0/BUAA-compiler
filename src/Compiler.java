import ASTNode.CompUnitNode;
import Token.Token;
import error.Error;
import error.ErrorTuple;
import frontend.Lexer;
import frontend.Parser;
import backend.BackEnd;
import midend.MidEnd;
import midend.symbol.SymbolManager;
import optimize.OptimizeManager;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;
import java.util.List;

public class Compiler {
    public static void main(String[] args) throws IOException {
        // 读取文件
        String input = Files.readString(Paths.get("testfile.txt"));

        // 1. 词法分析
        Lexer lexer = new Lexer(input);
        
        PrintStream lexerOut = new PrintStream(new FileOutputStream("lexer.txt"));
        for (Token token : lexer.getTokens()) {
            lexerOut.println(token.print());
        }
        lexerOut.close();

        // 2. 语法分析
        Parser parser = new Parser(lexer.getTokens());
        CompUnitNode root = parser.getCompUnitNode();

        PrintStream parserOut = new PrintStream(new FileOutputStream("parser.txt"));
        PrintStream originalOut = System.out;
        System.setOut(parserOut);
        if (root != null) {
            parser.print();
        }
        parserOut.close();
        System.setOut(originalOut);

        // 3. 语义分析
        if (root != null) {
            MidEnd.generateSymbolTable(root);
        }

        // 4. 错误处理
        Error error = Error.getInstance();
        List<ErrorTuple> errors = error.getErrorTuples();
        
        if (errors.isEmpty()) {
            PrintStream symbolOut = new PrintStream(new FileOutputStream("symbol.txt"));
            symbolOut.print(SymbolManager.GetSymbolTable().toString());
            symbolOut.close();
            
            // 5. 中间代码生成
            midend.llvm.IrModule module = MidEnd.generateIR(root);
            PrintStream irOut = new PrintStream(new FileOutputStream("llvm_ir.txt"));
            irOut.print(module.toString());
            irOut.close();

            // 6. 中端优化接口（当前为空实现）
            OptimizeManager.Init(module);
            OptimizeManager.Optimize();
            PrintStream irOptOut = new PrintStream(new FileOutputStream("llvm_ir_opt.txt"));
            irOptOut.print(module.toString());
            irOptOut.close();

            // 7. 后端生成 MIPS
            BackEnd.GenerateMips();
            PrintStream mipsOut = new PrintStream(new FileOutputStream("mips.txt"));
            mipsOut.print(BackEnd.GetMipsModule().toString());
            mipsOut.close();
        } else {
            PrintStream errorOut = new PrintStream(new FileOutputStream("error.txt"));
            error.sortByLineNumber();
            for (ErrorTuple e : errors) {
                errorOut.println(e.toString());
            }
            errorOut.close();
        }
    }
}
