package ASTNode;
import Token.*;
import Utils.TokenIterator;

import static ASTNode.DeclNode.parseDeclNode;
import static ASTNode.StmtNode.parseStmtNode;

public class BlockItemNode {
    // BlockItem -> Decl | Stmt
    private DeclNode declNode;
    private StmtNode stmtNode;
    private static TokenIterator tokenIterator = TokenIterator.getInstance();

    public BlockItemNode(DeclNode declNode, StmtNode stmtNode) {
        this.declNode = declNode;
        this.stmtNode = stmtNode;
    }

    public DeclNode getDeclNode() {
        return declNode;
    }

    public StmtNode getStmtNode() {
        return stmtNode;
    }

    public static BlockItemNode parseBlockItemNode() {
        DeclNode declNode = null;
        StmtNode stmtNode = null;
        if (tokenIterator.getCurrentToken().getTokenType() == TokenType.CONSTTK || tokenIterator.getCurrentToken().getTokenType() == TokenType.INTTK || tokenIterator.getCurrentToken().getTokenType() == TokenType.STATICTK) {
            declNode = parseDeclNode();
        } else {
            stmtNode = parseStmtNode();
        }
        return new BlockItemNode(declNode, stmtNode);
    }

    public void buildSymbolTable() {
        if (declNode != null) {
            declNode.buildSymbolTable();
        } else {
            stmtNode.buildSymbolTable();
        }
    }

    public boolean isReturnStmt() {
        return stmtNode != null && stmtNode.getType() == StmtNode.StmtType.Return;
    }

    public void print() {
        if (declNode != null) {
            declNode.print();
        } else {
            stmtNode.print();
        }
    }
}
