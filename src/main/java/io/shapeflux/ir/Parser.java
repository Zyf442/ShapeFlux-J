package io.shapeflux.ir;

import java.util.*;

/** Whitelisted expression DSL; no Java fragments, reflection, imports or file access. */
public final class Parser {
    private static final int MAX_NODES = 200, MAX_DEPTH = 48;
    private final String text;
    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Node> names = new LinkedHashMap<>();
    private int pos, depth;
    private String token;
    private Parser(String text) {
        if (text == null || text.length() > 16000) throw new IllegalArgumentException("DSL must contain at most 16000 characters");
        this.text = text;
        names.put("x", make(Op.X, 0)); names.put("bias", make(Op.BIAS, 0));
        next();
    }
    public static Program parse(String text) { return new Parser(text).program(); }
    private Program program() {
        Node out = null;
        Program.Terminal terminal = Program.Terminal.MAP;
        while (!token.isEmpty()) {
            if (out != null) fail("out must be the last statement");
            if (take("let")) {
                String name = token;
                if (!name.matches("[A-Za-z_][A-Za-z_0-9]*") || Set.of("let", "out").contains(name) || Program.Terminal.function(name) != null || names.containsKey(name))
                    fail("Invalid or duplicate variable: " + name);
                next(); expect("="); Node value = expression(); expect(";"); names.put(name, value);
            } else {
                expect("out"); expect("=");
                out = expression();
                if(out.op().rowwise()) {
                    terminal=Program.Terminal.function(out.op().name().toLowerCase(Locale.ROOT));
                    out=out.args().get(0);
                }
                expect(";");
            }
        }
        if (out == null) fail("Missing out = expression; statement");
        return new Program(nodes, out, terminal);
    }
    private Node expression() {
        Node n = term();
        while (token.equals("+") || token.equals("-")) { String op = token; next(); n = make(op.equals("+") ? Op.ADD : Op.SUB, 0, n, term()); }
        return n;
    }
    private Node term() {
        Node n = unary();
        while (token.equals("*") || token.equals("/")) { String op = token; next(); n = make(op.equals("*") ? Op.MUL : Op.DIV, 0, n, unary()); }
        return n;
    }
    private Node unary() {
        if (++depth > MAX_DEPTH) fail("Expression nesting limit exceeded");
        try {
            if (take("-")) return make(Op.NEG, 0, unary());
            if (take("+")) return unary();
            if (take("(")) { Node n = expression(); expect(")"); return n; }
            String name = token; next();
            if (name.matches("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
                double v;
                try { v = Double.parseDouble(name); } catch (NumberFormatException e) { fail("Invalid number"); return null; }
                if (!Double.isFinite(v)) fail("Constants must be finite");
                return make(Op.CONSTANT, v);
            }
            if (take("(")) {
                Op op;
                try { op = Op.valueOf(name.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { fail("Unknown function: " + name); return null; }
                if (op.arity() == 0 || Set.of(Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.NEG).contains(op)) fail("Unsupported function: " + name);
                Node a = expression(), b = null;
                if (op.arity() == 2) { expect(","); b = expression(); }
                expect(")");
                return op.arity() == 1 ? make(op, 0, a) : make(op, 0, a, b);
            }
            Node n = names.get(name);
            if (n == null) fail("Undefined variable: " + name);
            return n;
        } finally { depth--; }
    }
    private Node make(Op op, double value, Node... args) {
        if (nodes.size() >= MAX_NODES) fail("Program exceeds " + MAX_NODES + " IR nodes");
        Node node = new Node(nodes.size(), op, value, Arrays.asList(args)); nodes.add(node); return node;
    }
    private boolean take(String value) { if (!token.equals(value)) return false; next(); return true; }
    private void expect(String value) { if (!take(value)) fail("Expected '" + value + "', got '" + token + "'"); }
    private void next() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c)) { pos++; continue; }
            if (c == '#' || (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/')) {
                while (pos < text.length() && text.charAt(pos) != '\n') pos++;
                continue;
            }
            break;
        }
        if (pos == text.length()) { token = ""; return; }
        int start = pos; char c = text.charAt(pos++);
        if (Character.isLetter(c) || c == '_') {
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) pos++;
        } else if (Character.isDigit(c) || c == '.') {
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) pos++;
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++; if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
        } else if ("+-*/=();,".indexOf(c) < 0) fail("Illegal character: " + c);
        token = text.substring(start, pos);
    }
    private void fail(String message) { throw new IllegalArgumentException(message + " (near character " + pos + ")"); }
}
