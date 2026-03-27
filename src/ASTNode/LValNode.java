package ASTNode;

import Token.*;
import Utils.TokenIterator;
import error.Error;
import midend.symbol.Symbol;
import midend.symbol.SymbolManager;
import midend.symbol.ValueSymbol;

import java.util.ArrayList;

import static ASTNode.ExpNode.parseExpNode;

public class LValNode {
    // LVal -> Ident {'[' Exp ']'}
    private Token ident;
    private ArrayList<Token> leftBrackets;
    private ArrayList<ExpNode> expNodes;
    private ArrayList<Token> rightBrackets;
    private static TokenIterator tokenIterator = TokenIterator.getInstance();

    public LValNode(Token ident, ArrayList<Token> leftBrackets, ArrayList<ExpNode> expNodes, ArrayList<Token> rightBrackets) {
        this.ident = ident;
        this.leftBrackets = leftBrackets;
        this.expNodes = expNodes;
        this.rightBrackets = rightBrackets;
    }

    public Token getIdent() {
        return ident;
    }

    public ArrayList<ExpNode> getExpNodes() {
        return expNodes;
    }

    public static LValNode parseLValNode() {
        Token ident = tokenIterator.match(TokenType.IDENFR);
        ArrayList<Token> leftBrackets = new ArrayList<>();
        ArrayList<ExpNode> expNodes = new ArrayList<>();
        ArrayList<Token> rightBrackets = new ArrayList<>();
        while (tokenIterator.getCurrentToken().getTokenType() == TokenType.LBRACK) {
            leftBrackets.add(tokenIterator.match(TokenType.LBRACK));
            expNodes.add(parseExpNode());
            rightBrackets.add(tokenIterator.match(TokenType.RBRACK));
        }
        return new LValNode(ident, leftBrackets, expNodes, rightBrackets);
    }

    public void buildSymbolTable() {
        Symbol symbol = SymbolManager.GetSymbol(ident.getValue());
        if (symbol == null) {
            Error.getInstance().addError(ident.getLine_number(), "c");
        }

        for (ExpNode node : expNodes) {
            node.buildSymbolTable();
        }
    }

    public boolean isConst() {
        Symbol symbol = SymbolManager.GetSymbol(ident.getValue());
        if (symbol instanceof ValueSymbol) {
            return ((ValueSymbol) symbol).IsConst();
        }
        return false;
    }

    public int getDimension() {
        Symbol symbol = SymbolManager.GetSymbol(ident.getValue());
        if (symbol instanceof ValueSymbol) {
            ValueSymbol valueSymbol = (ValueSymbol) symbol;
            return valueSymbol.GetDimension() - leftBrackets.size();
        }
        return 0;
    }

    public void print() {
        System.out.println(ident.print());
        for (int i = 0; i < leftBrackets.size(); i++) {
            System.out.println(leftBrackets.get(i).print());
            expNodes.get(i).print();
            System.out.println(rightBrackets.get(i).print());
        }
        System.out.println(NodeString.get(NodeType.LVal));
    }


}
