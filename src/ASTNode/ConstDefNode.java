package ASTNode;

import Token.*;
import Utils.TokenIterator;
import midend.symbol.SymbolManager;
import midend.symbol.SymbolType;
import midend.symbol.ValueSymbol;

import java.util.ArrayList;

import static ASTNode.ConstExpNode.parseConstExpNode;
import static ASTNode.ConstInitValNode.parseConstInitValNode;

public class ConstDefNode {
    // ConstDef -> Ident { '[' ConstExp ']' } '=' ConstInitVal
    private Token ident;
    private ArrayList<Token> leftBrackets;
    private ArrayList<ConstExpNode> constExpNodes;
    private ArrayList<Token> rightBrackets;
    private Token equalToken;
    private ConstInitValNode constInitValNode;
    private static TokenIterator tokenIterator = TokenIterator.getInstance();

    public ConstDefNode(Token ident, ArrayList<Token> leftBrackets, ArrayList<ConstExpNode> constExpNodes, ArrayList<Token> rightBrackets, Token equalToken, ConstInitValNode constInitValNode) {
        this.ident = ident;
        this.leftBrackets = leftBrackets;
        this.constExpNodes = constExpNodes;
        this.rightBrackets = rightBrackets;
        this.equalToken = equalToken;
        this.constInitValNode = constInitValNode;
    }

    public Token getIdent() {
        return ident;
    }

    public ArrayList<Token> getLeftBrackets() {
        return leftBrackets;
    }

    public ArrayList<ConstExpNode> getConstExpNodes() {
        return constExpNodes;
    }

    public ArrayList<Token> getRightBrackets() {
        return rightBrackets;
    }

    public Token getEqualToken() {
        return equalToken;
    }

    public ConstInitValNode getConstInitValNode() {
        return constInitValNode;
    }

    public static ConstDefNode parseConstDefNode() {
        Token ident = tokenIterator.match(TokenType.IDENFR);
        ArrayList<Token> leftBrackets = new ArrayList<>();
        ArrayList<ConstExpNode> constExpNodes = new ArrayList<>();
        ArrayList<Token> rightBrackets = new ArrayList<>();
        Token equalToken ;
        while(tokenIterator.getCurrentToken().getTokenType() == TokenType.LBRACK) {
            leftBrackets.add(tokenIterator.match(TokenType.LBRACK));
            constExpNodes.add(parseConstExpNode());
            rightBrackets.add(tokenIterator.match(TokenType.RBRACK));
        }
        equalToken = tokenIterator.match(TokenType.ASSIGN);
        ConstInitValNode constInitValNode = parseConstInitValNode();
        return new ConstDefNode(ident,leftBrackets,constExpNodes,rightBrackets,equalToken,constInitValNode);
    }

    public void buildSymbolTable(String type) {
        int dimension = constExpNodes.size();
        SymbolType symbolType = SymbolType.GetConstType(type, dimension);
        ValueSymbol symbol = new ValueSymbol(ident.getValue(), symbolType, dimension);
        symbol.SetIsConst(true);
        SymbolManager.AddSymbol(symbol, ident.getLine_number());

        for (ConstExpNode node : constExpNodes) {
            node.buildSymbolTable();
        }
        constInitValNode.buildSymbolTable();
    }

    public void print() {
        System.out.println(ident.print());
        for (int i = 0; i < constExpNodes.size(); i++) {
            System.out.println(leftBrackets.get(i).print());
            constExpNodes.get(i).print();
            System.out.println(rightBrackets.get(i).print());
        }
        System.out.println(equalToken.print());
        constInitValNode.print();
        System.out.println(NodeString.get(NodeType.ConstDef));
    }
}
