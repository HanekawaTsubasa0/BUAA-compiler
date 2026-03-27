package ASTNode;

import Token.*;
import Utils.TokenIterator;
import error.Error;
import midend.symbol.FuncSymbol;
import midend.symbol.Symbol;
import midend.symbol.SymbolManager;
import midend.symbol.SymbolType;

import java.util.ArrayList;

import static ASTNode.BlockNode.parseBlockNode;
import static ASTNode.FuncFParamsNode.parseFuncFParamsNode;
import static ASTNode.FuncTypeNode.parseFuncTypeNode;

public class FuncDefNode {
    // FuncDef -> FuncType Ident '(' [FuncFParams] ')' Block

    private FuncTypeNode funcTypeNode;
    private Token ident;
    private Token leftParentToken;
    private FuncFParamsNode funcFParamsNode;
    private Token rightParentToken;
    private BlockNode blockNode;
    private static TokenIterator tokenIterator = TokenIterator.getInstance();


    private boolean paramsError = false;

    public FuncDefNode(FuncTypeNode funcTypeNode, Token ident, Token leftParentToken, FuncFParamsNode funcFParamsNode, Token rightParentToken, BlockNode blockNode, boolean paramsError) {
        this.funcTypeNode = funcTypeNode;
        this.ident = ident;
        this.leftParentToken = leftParentToken;
        this.funcFParamsNode = funcFParamsNode;
        this.rightParentToken = rightParentToken;
        this.blockNode = blockNode;
        this.paramsError = paramsError;
    }

    public FuncTypeNode getFuncTypeNode() {
        return funcTypeNode;
    }

    public Token getIdent() {
        return ident;
    }

    public FuncFParamsNode getFuncFParamsNode() {
        return funcFParamsNode;
    }

    public BlockNode getBlockNode() {
        return blockNode;
    }

    public static FuncDefNode parseFuncDefNode() {
        FuncTypeNode funcTypeNode = parseFuncTypeNode();
        Token ident = tokenIterator.match(TokenType.IDENFR);
        Token leftParentToken = tokenIterator.match(TokenType.LPARENT);
        FuncFParamsNode funcParamsNode = null;
        if (tokenIterator.getCurrentToken().getTokenType() == TokenType.INTTK) {
            funcParamsNode = parseFuncFParamsNode();
        }
        
        boolean hasError = false;
        if (tokenIterator.getCurrentToken().getTokenType() != TokenType.RPARENT) {
            hasError = true;
        }
        Token rightParentToken = tokenIterator.match(TokenType.RPARENT);
        
        BlockNode blockNode = parseBlockNode();
        return new FuncDefNode(funcTypeNode, ident, leftParentToken, funcParamsNode, rightParentToken, blockNode, hasError);
    }

    public void buildSymbolTable() {
        String type = funcTypeNode.getType();
        FuncSymbol symbol = new FuncSymbol(ident.getValue(), SymbolType.GetFuncType(type));
        if (paramsError) {
            symbol.setIgnoreParamCheck(true);
        }
        SymbolManager.AddSymbol(symbol, ident.getLine_number());

        SymbolManager.EnterFunc(SymbolType.GetFuncType(type));
        SymbolManager.CreateSonSymbolTable();

        if (funcFParamsNode != null) {
            ArrayList<Symbol> params = funcFParamsNode.buildSymbolTable();
            symbol.SetFormalParamList(params);
        }

        blockNode.buildSymbolTable();

        checkReturnError(type);

        SymbolManager.GoToFatherSymbolTable();
        SymbolManager.LeaveFunc();
    }

    private void checkReturnError(String type) {
        if ("void".equals(type)) return;

        BlockItemNode last = blockNode.getLastBlockItemNode();
        if (last == null || !last.isReturnStmt()) {
            Error.getInstance().addError(blockNode.getRightBraceToken().getLine_number(), "g");
        }
    }

    public void print() {
        funcTypeNode.print();
        System.out.println(ident.print());
        System.out.println(leftParentToken.print());
        if (funcFParamsNode != null) {
            funcFParamsNode.print();
        }
        System.out.println(rightParentToken.print());
        blockNode.print();
        System.out.println(NodeString.get(NodeType.FuncDef));
    }
}
