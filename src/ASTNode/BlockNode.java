package ASTNode;

import Token.*;
import Utils.TokenIterator;

import java.util.ArrayList;

import static ASTNode.BlockItemNode.parseBlockItemNode;

public class BlockNode {
    // Block -> '{' { BlockItem } '}'

    private Token leftBraceToken;
    private ArrayList<BlockItemNode> blockItemNodes;
    private Token rightBraceToken;
    private static TokenIterator tokenIterator = TokenIterator.getInstance();

    public BlockNode(Token leftBraceToken, ArrayList<BlockItemNode> blockItemNodes, Token rightBraceToken) {
        this.leftBraceToken = leftBraceToken;
        this.blockItemNodes = blockItemNodes;
        this.rightBraceToken = rightBraceToken;
    }

    public ArrayList<BlockItemNode> getBlockItemNodes() {
        return blockItemNodes;
    }

    public Token getRightBraceToken() {
        return rightBraceToken;
    }

    public static BlockNode parseBlockNode() {
        Token leftBraceToken = tokenIterator.match(TokenType.LBRACE);
        ArrayList<BlockItemNode> blockItemNodes = new ArrayList<>();
        while (tokenIterator.getCurrentToken().getTokenType() != TokenType.RBRACE) {
            blockItemNodes.add(parseBlockItemNode());
        }
        Token rightBraceToken = tokenIterator.match(TokenType.RBRACE);
        return new BlockNode(leftBraceToken, blockItemNodes, rightBraceToken);
    }

    public void buildSymbolTable() {
        for (BlockItemNode node : blockItemNodes) {
            node.buildSymbolTable();
        }
    }

    public BlockItemNode getLastBlockItemNode() {
        if (blockItemNodes.isEmpty()) return null;
        return blockItemNodes.get(blockItemNodes.size() - 1);
    }

    public void print() {
        System.out.println(leftBraceToken.print());
        for (BlockItemNode blockItemNode : blockItemNodes) {
            blockItemNode.print();
        }
        System.out.println(rightBraceToken.print());
        System.out.println(NodeString.get(NodeType.Block));
    }
}
