/*
 * XmlMode.java
 *
 * Copyright (C) 1998-2003 Peter Graves
 * $Id: XmlMode.java,v 1.3 2003-06-04 18:12:35 piso Exp $
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j;

import gnu.regexp.RE;
import gnu.regexp.REException;
import gnu.regexp.REMatch;
import gnu.regexp.UncheckedRE;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.undo.CompoundEdit;
import org.xml.sax.SAXParseException;

public final class XmlMode extends AbstractMode implements Constants, Mode
{
    private static final String COMMENT_START = "<!--";
    private static final String COMMENT_END   = "-->";

    private static final Mode mode = new XmlMode();

    private static RE tagNameRE;
    private static RE attributeNameRE;
    private static RE quotedValueRE;
    private static RE unquotedValueRE;

    private XmlMode()
    {
        super(XML_MODE, XML_MODE_NAME);
        setProperty(Property.INDENT_SIZE, 2);
    }

    public static final Mode getMode()
    {
        return mode;
    }

    public NavigationComponent getSidebarComponent(Editor editor)
    {
        Debug.assertTrue(editor.getBuffer().getMode() == getMode());
        if (!editor.getBuffer().getBooleanProperty(Property.ENABLE_TREE))
            return null;
        View view = editor.getCurrentView();
        if (view == null)
            return null; // Shouldn't happen.
        if (view.getSidebarComponent() == null)
            view.setSidebarComponent(new XmlTree(editor, null));
        return view.getSidebarComponent();
    }

    public String getCommentStart()
    {
        return "<!--";
    }

    public String getCommentEnd()
    {
        return "-->";
    }

    public Formatter getFormatter(Buffer buffer)
    {
        return new XmlFormatter(buffer);
    }

    protected void setKeyMapDefaults(KeyMap km)
    {
        km.mapKey(KeyEvent.VK_TAB, 0, "tab");
        km.mapKey(KeyEvent.VK_TAB, CTRL_MASK, "insertTab");
        km.mapKey(KeyEvent.VK_ENTER, 0, "newlineAndIndent");
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK, "newline");
        km.mapKey(KeyEvent.VK_M, CTRL_MASK, "xmlFindMatch");
        km.mapKey('=', "xmlElectricEquals");
        km.mapKey('>', "electricCloseAngleBracket");
        km.mapKey(KeyEvent.VK_L, CTRL_MASK | SHIFT_MASK, "sidebarListTags");
        km.mapKey(KeyEvent.VK_E, CTRL_MASK, "xmlInsertMatchingEndTag");
        km.mapKey('/', "xmlElectricSlash");
        km.mapKey(KeyEvent.VK_I, ALT_MASK, "cycleIndentSize");

        // These are the "normal" mappings.
        km.mapKey(KeyEvent.VK_COMMA, CTRL_MASK | SHIFT_MASK, "xmlInsertTag");
        km.mapKey(KeyEvent.VK_PERIOD, CTRL_MASK | SHIFT_MASK,
            "xmlInsertEmptyElementTag");

        // The "normal" mappings don't work for Blackdown 1.2.2, but these do.
        km.mapKey(0x7c, CTRL_MASK | SHIFT_MASK, "xmlInsertTag");
        km.mapKey(0x7e, CTRL_MASK | SHIFT_MASK, "xmlInsertEmptyElementTag");

        km.mapKey(KeyEvent.VK_P, CTRL_MASK, "xmlParseBuffer");
        km.mapKey(KeyEvent.VK_EQUALS, CTRL_MASK, "xmlFindCurrentNode");

        // build.xml
        km.mapKey(KeyEvent.VK_F9, 0, "compile");
        km.mapKey(KeyEvent.VK_F9, CTRL_MASK, "recompile");
    }

    public void loadFile(Buffer buffer, File file)
    {
        String encoding = null;
        try {
            BufferedReader reader =
                new BufferedReader(new InputStreamReader(file.getInputStream()));
            String s = reader.readLine();
            reader.close();
            if (s != null && s.toLowerCase().startsWith("<?xml")) {
                int end = s.indexOf("?>");
                if (end >= 0) {
                    s = s.substring(5, end);
                    RE re = new UncheckedRE("encoding[ \t]*=[ \t]*");
                    REMatch match = re.getMatch(s);
                    if (match != null) {
                        // First char after match will be single or double
                        // quote.
                        s = s.substring(match.getEndIndex());
                        if (s.length() > 0) {
                            char quoteChar = s.charAt(0);
                            // Find matching quote char.
                            end = s.indexOf(quoteChar, 1);
                            if (end >= 0) {
                                encoding = s.substring(1, end);
                                if (Utilities.isSupportedEncoding(encoding))
                                    file.setEncoding(encoding);
                                else
                                    Log.error("unsupported encoding \"" +
                                        encoding + '"');
                            }
                        }
                    }
                }
            }
        }
        catch (IOException e) {
            Log.error(e);
        }
        if (encoding == null)
            encoding = "UTF8"; // Default for XML.
        try {
            buffer.load(file.getInputStream(), file.getEncoding());
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    public boolean canIndent()
    {
        return true;
    }

    public int getCorrectIndentation(Line line, Buffer buffer)
    {
        final Line model = getModel(line);
        if (model == null)
            return 0;
        int indent = buffer.getIndentation(model);
        if (line.flags() == STATE_QUOTE && model.flags() != STATE_QUOTE)
            return indent + buffer.getIndentSize();
        final String text = line.trim();
        if (text.equals("/>")) {
            Position pos = new Position(line, line.length());
            while (pos.prev()) {
                if (pos.getChar() == '<')
                    break;
            }
            return buffer.getIndentation(pos.getLine());
        }
        if (text.startsWith("</")) {
            Position pos = findMatchingStartTag(line);
            if (pos != null)
                return buffer.getIndentation(pos.getLine());
            indent -= buffer.getIndentSize();
            return indent < 0 ? 0 : indent;
        }
        final String modelText = model.trim();
        if (modelText.startsWith("<") && !modelText.startsWith("</")) {
            String tag = getTag(modelText);
            if (isEmptyElementTag(tag))
                return indent;
            if (isProcessingInstruction(tag))
                return indent;
            // Model starts with start tag.
            String tagName = Utilities.getTagName(modelText);
            String startTag = "<"  + tagName;
            String endTag = "</" + tagName;
            int count = 1;
            final int limit = modelText.length();
            for (int i = startTag.length(); i < limit; i++) {
                if (modelText.charAt(i) == '<') {
                    if (lookingAt(modelText, i, endTag)) {
                        int end = i + endTag.length();
                        if (end < limit) {
                            char c = modelText.charAt(end);
                            if (c == ' ' || c == '\t' || c == '>')
                                --count;
                        }
                    } else if (lookingAt(modelText, i, startTag)) {
                        int end = i + startTag.length();
                        if (end < limit) {
                            char c = modelText.charAt(end);
                            if (c == ' ' || c == '\t' || c == '>')
                                ++count;
                        }
                    }
                }
            }
            if (count > 0)
                indent += buffer.getIndentSize();
        } else if (modelText.endsWith("/>")) {
            Position pos = new Position(model, model.length());
            while (pos.prev()) {
                if (pos.getChar() == '<')
                    break;
            }
            indent = buffer.getIndentation(pos.getLine());
        }
        return indent;
    }

    // Line must start with an end tag.
    private Position findMatchingStartTag(Line line)
    {
        String s = line.trim();
        if (!s.startsWith("</"))
            return null;
        FastStringBuffer sb = new FastStringBuffer();
        for (int i = 2; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ')
                break;
            if (c == '>')
                break;
            sb.append(c);
        }
        Position pos = new Position(line, 0);
        String name = sb.toString();
        return findMatchingStartTag(name, pos);
    }

    private static Position findMatchingStartTag(String name, Position start)
    {
        Position pos = start.copy();
        String endTagToBeMatched = "</" + name + ">";
        String lookFor = "<" + name;
        int count = 1;
        boolean succeeded = false;

        if (pos.lookingAt(endTagToBeMatched))
            pos.prev();

        // Search backward.
        while (!pos.atStart()) {
            if (pos.lookingAt(COMMENT_END)) {
                do {
                    pos.prev();
                } while (!pos.atStart() && !pos.lookingAt(COMMENT_START));
            } else if (pos.lookingAt(endTagToBeMatched)) {
                ++count;
            } else if (pos.lookingAt(lookFor)) {
                // getTag() skips past the tag, so use pos.copy() here since
                // we're moving backwards not forwards.
                String tag = getTag(pos.copy());
                if (Utilities.getTagName(tag).equals(name)) {
                    if (!tag.endsWith("/>")) {
                        --count;
                        if (count == 0) {
                            succeeded = true;
                            break;
                        }
                    }
                }
            }
            pos.prev();
        }
        if (succeeded)
            return pos;
        // Not found.
        return null;
    }

    private static Position findMatchingEndTag(String name, Position start)
    {
        Position pos = start.copy();
        String startTagToBeMatched = "<" + name;
        String lookFor = "</" + name + ">";
        int count = 1;

        if (pos.lookingAt(startTagToBeMatched))
            pos.skip(startTagToBeMatched.length());

        // Search forward.
        while (!pos.atEnd()) {
            if (pos.lookingAt(COMMENT_START)) {
                do {
                    pos.next();
                } while (!pos.atEnd() && !pos.lookingAt(COMMENT_END));
                if (pos.atEnd()) {
                    break;
                } else {
                    pos.skip(COMMENT_END.length());
                    continue;
                }
            }
            if (pos.lookingAt(startTagToBeMatched)) {
                String tag = getTag(pos); // Skips past tag.
                if (Utilities.getTagName(tag).equals(name)) {
                    if (!tag.endsWith("/>"))
                        ++count;
                }
                continue;
            }
            if (pos.lookingAt(lookFor)) {
                --count;
                if (count == 0) {
                    return pos;
                } else {
                    pos.skip(lookFor.length());
                    continue;
                }
            }
            // None of the above...
            pos.next();
        }
        // Not found.
        return null;
    }

    private static String getUnmatchedStartTag(Position start)
    {
        Position pos = start.copy();
        if (inComment(pos))
            return null;
        if (inTag(pos))
            return null;
        int count = 1;
        if (!pos.lookingAt("<") || pos.prev()) {
            do {
                if (pos.lookingAt(COMMENT_END)) {
                    do {
                        pos.prev();
                    } while (!pos.atStart() && !pos.lookingAt(COMMENT_START));
                } else if (pos.lookingAt("</")) {
                    ++count;
                } else if (pos.lookingAt("<")) {
                    // getTag() skips past the tag, so use pos.copy() here
                    // since we're moving backwards not forwards.
                    String tag = getTag(pos.copy());
                    if (!tag.endsWith("/>")) {
                        --count;
                        if (count == 0)
                            return tag;
                    }
                }
            } while (pos.prev());
        }
        // Not found.
        return null;
    }

    private static boolean inTag(Position position)
    {
        Position pos = position.copy();
        while (pos.prev()) {
            if (pos.lookingAt(COMMENT_END)) {
                do {
                    pos.prev();
                } while (!pos.atStart() && !pos.lookingAt(COMMENT_START));
                continue;
            }
            char c = pos.getChar();
            if (c == '<')
                return true;
            else if (c == '>')
                return false;
        }
        return false;
    }

    private static boolean inComment(Position position)
    {
        Position pos = position.copy();
        boolean inComment = pos.getLine().flags() == STATE_COMMENT;
        pos.setOffset(0);
        final int limit = position.getOffset();
        while (pos.getOffset() < limit) {
            if (inComment) {
                if (pos.lookingAt(COMMENT_END)) {
                    pos.skip(COMMENT_END.length());
                    if (pos.getOffset() > limit)
                        break;
                    inComment = false;
                    continue;
                }
            } else if (pos.lookingAt(COMMENT_START)) {
                inComment = true;
                pos.skip(COMMENT_START.length());
                continue;
            }
            pos.next();
        }
        return inComment;
    }

    private static String getTag(String s)
    {
        if (s == null || s.length() == 0 || s.charAt(0) != '<')
            return null;
        FastStringBuffer sb = new FastStringBuffer();
        final int limit = s.length();
        char quoteChar = 0;
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            sb.append(c);
            if (quoteChar != 0) {
                // We're in a quoted section.
                if (c == quoteChar)
                    quoteChar = 0;
            } else {
                // We're not in a quoted section.
                if (c == '\'' || c == '"')
                    quoteChar = c;
                else if (c == '>')
                    break;
            }
        }
        return sb.toString();
    }

    // Advances position to first char past end of tag.
    private static String getTag(Position pos)
    {
        if (pos == null || pos.getChar() != '<')
            return null;
        FastStringBuffer sb = new FastStringBuffer('<');
        char quoteChar = 0;
        while (pos.next()) {
            char c = pos.getChar();
            sb.append(c);
            if (quoteChar != 0) {
                // We're in a quoted section.
                if (c == quoteChar)
                    quoteChar = 0;
            } else {
                // We're not in a quoted section.
                if (c == '\'' || c == '"')
                    quoteChar = c;
                else if (c == '>') {
                    pos.next();
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static boolean isProcessingInstruction(String tag)
    {
        if (tag.startsWith("<?") && tag.endsWith("?>"))
            return true;
        return false;
    }

    private static boolean isEmptyElementTag(String tag)
    {
        if (tag == null)
            return false;
        return tag.endsWith("/>");
    }

    private static final boolean lookingAt(String s, int i, String pattern)
    {
        return s.regionMatches(i, pattern, 0, pattern.length());
    }

    private static Line getModel(Line line)
    {
        Line model = line;
        while ((model = model.previous()) != null) {
            int flags = model.flags();
            if (flags == STATE_COMMENT || flags == STATE_QUOTE)
                continue;
            else if (model.trim().startsWith(COMMENT_START))
                continue;
            else if (model.isBlank())
                continue;
            else
                break;
        }
        return model;
    }

    public char fixCase(Editor editor, char c)
    {
        if (!Character.isUpperCase(c) && !Character.isLowerCase(c))
            return c;
        final Buffer buffer = editor.getBuffer();
        if (!buffer.getBooleanProperty(Property.FIX_CASE))
            return c;
        if (!initRegExps())
            return c;
        Position pos = findStartOfTag(editor.getDot());
        if (pos != null) {
            int index = pos.getOffset();
            String text = pos.getLine().getText();
            REMatch match = tagNameRE.getMatch(text, index);
            if (match == null)
                return c;
            if (match.getEndIndex() >= editor.getDotOffset()) {
                // Tag name.
                if (buffer.getBooleanProperty(Property.UPPER_CASE_TAG_NAMES))
                    return Character.toUpperCase(c);
                else
                    return Character.toLowerCase(c);
            }
            while (true) {
                index = match.getEndIndex();
                match = attributeNameRE.getMatch(text, index);
                if (match == null)
                    return c;
                if (match.getEndIndex() >= editor.getDotOffset()) {
                    // Attribute name.
                    if (buffer.getBooleanProperty(Property.UPPER_CASE_ATTRIBUTE_NAMES))
                        return Character.toUpperCase(c);
                    else
                        return Character.toLowerCase(c);
                }
                index = match.getEndIndex();
                match = quotedValueRE.getMatch(text, index);
                if (match == null) {
                    match = unquotedValueRE.getMatch(text, index);
                    if (match == null)
                        return c;
                }
                if (match.getEndIndex() >= editor.getDotOffset()) {
                    // Attribute value.
                    return c;
                }
            }
        }
        return c;
    }

    private static boolean checkElectricEquals(Editor editor)
    {
        Position pos = findStartOfTag(editor.getDot());
        if (pos == null)
            return false;
        char c = editor.getDotChar();
        if (c == '>' || c == '/' || Character.isWhitespace(c))
            return true;
        return false;
    }

    // Scans backward on same line for '<'.
    private static Position findStartOfTag(Position pos)
    {
        final String text = pos.getLine().getText();
        int offset = pos.getOffset();
        if (offset >= pos.getLine().length())
            offset = pos.getLine().length()-1;
        else if (text.charAt(offset) == '>')
            --offset;
        while (offset >= 0) {
            char c = text.charAt(offset);
            if (c == '<')
                return new Position(pos.getLine(), offset);
            if (c == '>')
                return null;
            --offset;
        }
        return null;
    }

    private static boolean initRegExps()
    {
        if (tagNameRE == null) {
            try {
                tagNameRE = new RE("</?[A-Za-z0-9]*");
                attributeNameRE = new RE("\\s+[A-Za-z0-9]*");
                quotedValueRE = new RE("\\s*=\\s*\"[^\"]*");
                unquotedValueRE = new RE("\\s*=\\s*\\S*");
            }
            catch (REException e) {
                tagNameRE = null;
                return false;
            }
        }
        return true;
    }

    public static void xmlFindCurrentNode()
    {
        final Editor editor = Editor.currentEditor();
        if (editor.getModeId() == XML_MODE) {
            final Sidebar sidebar = editor.getSidebar();
            if (sidebar != null) {
                XmlTree tree = (XmlTree) sidebar.getBottomComponent();
                if (tree != null)
                    xmlEnsureCurrentNodeIsVisible(editor, tree);
            }
        }
    }

    public static void xmlParseBuffer()
    {
        final Editor editor = Editor.currentEditor();
        if (editor.getModeId() == XML_MODE) {
            XmlTree tree = null;
            final Sidebar sidebar = editor.getSidebar();
            if (sidebar != null) {
                tree = (XmlTree) sidebar.getBottomComponent();

                // If there's no tree...
                if (tree == null) {
                    sidebar.setUpdateFlag(SIDEBAR_NAVIGATION_COMPONENT_ALL);
                    sidebar.refreshSidebar();
                    tree = (XmlTree) sidebar.getBottomComponent();
                    if (tree != null)
                        xmlEnsureCurrentNodeIsVisible(editor, tree);
                    return;
                }
            }

            final Buffer buffer = editor.getBuffer();
            XmlParserImpl parser = new XmlParserImpl(buffer);
            if (parser.initialize()) {
                editor.setWaitCursor();
                parser.setReader(new StringReader(buffer.getText()));
                parser.run();
                editor.setDefaultCursor();
//                 Exception e = parser.getException();
//                 if (e != null) {
//                     if (e instanceof SAXParseException) {
//                         xmlFindError(editor, (SAXParseException)e);
//                         editor.showMessage((SAXParseException)e);
//                     }
//                 } else if (tree != null) {
//                     TreeModel treeModel = parser.getTreeModel();
//                     if (treeModel != null) {
//                         tree.setParserClassName(parser.getParserClassName());
//                         tree.setModel(treeModel);
//                         Debug.assertTrue(tree.getModel() != null);
//                         Debug.assertTrue(tree.getModel().getRoot() != null);
//                         xmlEnsureCurrentNodeIsVisible(editor, tree);
//                     }
//                     editor.status("No errors");
//                 }
                String output = parser.getOutput();
                // Note that with the current implementation, there will always
                // be output...
                if (output != null && output.length() > 0) {
                    OutputBuffer outputBuffer =
                        OutputBuffer.getOutputBuffer(output);
                    Editor otherEditor = editor.getOtherEditor();
                    if (otherEditor != null) {
                        outputBuffer.setUnsplitOnClose(
                            otherEditor.getBuffer().unsplitOnClose());
                        otherEditor.makeNext(outputBuffer);
                    } else
                        outputBuffer.setUnsplitOnClose(true);
                    editor.displayInOtherWindow(outputBuffer);
                } else {
                    if (tree != null) {
                        TreeModel treeModel = parser.getTreeModel();
                        if (treeModel != null) {
                            tree.setParserClassName(parser.getParserClassName());
                            tree.setModel(treeModel);
                            Debug.assertTrue(tree.getModel() != null);
                            Debug.assertTrue(tree.getModel().getRoot() != null);
                            xmlEnsureCurrentNodeIsVisible(editor, tree);
                        }
                    }
                    editor.status("No errors");
                }
            }
        }
    }

    public static void xmlValidateBuffer()
    {
        final Editor editor = Editor.currentEditor();
        if (editor.getModeId() == XML_MODE) {
            final Buffer buffer = editor.getBuffer();
            XmlParserImpl parser = new XmlParserImpl(buffer);
            if (parser.initialize()) {
                try {
                    editor.setWaitCursor();
                    parser.enableValidation(true);
                    parser.setReader(new StringReader(buffer.getText()));
                    parser.run();
                }
                finally {
                    editor.setDefaultCursor();
                }
                String output = parser.getOutput();
                if (output != null && output.length() > 0) {
                    OutputBuffer outputBuffer =
                        OutputBuffer.getOutputBuffer(output);
                    Editor otherEditor = editor.getOtherEditor();
                    if (otherEditor != null) {
                        outputBuffer.setUnsplitOnClose(
                            otherEditor.getBuffer().unsplitOnClose());
                        otherEditor.makeNext(outputBuffer);
                    } else
                        outputBuffer.setUnsplitOnClose(true);
                    editor.displayInOtherWindow(outputBuffer);
                } else
                    editor.status("No errors");
            }
        }
    }

    public static void xmlFindError(Editor editor, SAXParseException e)
    {
        Line line = editor.getBuffer().getLine(e.getLineNumber()-1);
        if (line != null) {
            int offset = e.getColumnNumber()-1;
            if (offset < 0)
                offset = 0;
            if (offset > line.length())
                offset = line.length();
            if (line != editor.getDotLine() || offset != editor.getDotOffset()) {
                editor.addUndo(SimpleEdit.MOVE);
                editor.updateDotLine();
                editor.setDot(line, offset);
                editor.updateDotLine();
                editor.moveCaretToDotCol();
                editor.updateDisplay();
            }
        }
    }

    public static void xmlEnsureCurrentNodeIsVisible(Editor editor, XmlTree tree)
    {
        if (tree == null)
            return;
        DefaultMutableTreeNode currentNode = tree.getNodeAtPos(editor.getDot());
        if (currentNode != null) {
            tree.scrollPathToVisible(new TreePath(currentNode.getPath()));
            tree.updatePosition();
        }
    }

    public static void copyXPath()
    {
        final Editor editor = Editor.currentEditor();
        if (editor.getModeId() == XML_MODE) {
            final Sidebar sidebar = editor.getSidebar();
            if (sidebar != null) {
                XmlTree tree = (XmlTree) sidebar.getBottomComponent();
                if (tree != null) {
                    DefaultMutableTreeNode currentNode =
                        tree.getNodeAtPos(editor.getDot());
                    if (currentNode != null) {
                        TreeNode[] array = currentNode.getPath();
                        if (array != null) {
                            FastStringBuffer sb = new FastStringBuffer();
                            for (int i = 0; i < array.length; i++) {
                                DefaultMutableTreeNode node =
                                    (DefaultMutableTreeNode) array[i];
                                XmlTreeElement element =
                                    (XmlTreeElement) node.getUserObject();
                                sb.append('/');
                                sb.append(element.getName());
                            }
                            if (sb.length() > 0) {
                                Editor.getKillRing().appendNew(sb.toString());
                                editor.copyKillToSystemClipboard();
                                editor.status("XPath copied to clipboard");
                            }
                        }
                    }
                }
            }
        }
    }

    public static void xmlElectricEquals()
    {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        boolean ok = false;
        if (editor.getModeId() == XML_MODE) {
            // Attributes always require quotes in XML mode.
            if (checkElectricEquals(editor))
                ok = true;
        }
        if (ok) {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.fillToCaret();
            editor.addUndo(SimpleEdit.INSERT_STRING);
            editor.insertStringInternal("=\"\"");
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().moveLeft();
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
        } else
            editor.insertNormalChar('=');
    }

    public static void xmlInsertTag()
    {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        InsertTagDialog d = new InsertTagDialog(editor);
        editor.centerDialog(d);
        d.show();
        String tagName = d.getTagName();
        if (tagName == null || tagName.length() == 0)
            return;
        // We always want the end tag in XML mode.
        InsertTagDialog.insertTag(editor, tagName, d.getExtra(), true);
    }

    public static void xmlInsertEmptyElementTag()
    {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        InputDialog d =
            new InputDialog(editor, "Tag:", "Insert Empty Element Tag", null);
        d.setHistory(new History("xmlInsertEmptyElementTag"));
        editor.centerDialog(d);
        d.show();
        String input = d.getInput();
        if (input == null)
            return;
        String tagName;
        String extra;
        int index = input.indexOf(' ');
        if (index >= 0) {
            tagName = input.substring(0, index);
            extra = input.substring(index);
        } else {
            tagName = input;
            extra = "";
        }
        final Buffer buffer = editor.getBuffer();
        if (buffer.getBooleanProperty(Property.FIX_CASE)) {
            if (buffer.getBooleanProperty(Property.UPPER_CASE_TAG_NAMES))
                tagName = tagName.toUpperCase();
            else
                tagName = tagName.toLowerCase();
        }
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        editor.fillToCaret();
        final int offset = editor.getDotOffset();
        editor.addUndo(SimpleEdit.INSERT_STRING);
        FastStringBuffer sb = new FastStringBuffer('<');
        sb.append(tagName);
        sb.append(extra);
        sb.append("/>");
        editor.insertStringInternal(sb.toString());
        Editor.updateInAllEditors(editor.getDotLine());
        editor.addUndo(SimpleEdit.MOVE);
        editor.getDot().setOffset(offset + 1 + input.length());
        editor.moveCaretToDotCol();
        editor.endCompoundEdit(compoundEdit);
    }

    public static void xmlFindMatch()
    {
        final Editor editor = Editor.currentEditor();
        final Position dot = editor.getDot();
        Position pos = findStartOfTag(dot);
        if (pos == null) {
            final Line dotLine = dot.getLine();
            int offset = dot.getOffset();
            if (dotLine.substring(0, offset).trim().length() == 0) {
                // We're in the whitespace to the left of the text on the line.
                // Skip to first non-whitespace char.
                while (Character.isWhitespace(dotLine.charAt(offset)) &&
                    offset < dotLine.length())
                    ++offset;
                if (dotLine.charAt(offset) == '<')
                    pos = new Position(dotLine, offset);
            }
            if (pos ==  null) {
                offset =
                    dotLine.getText().lastIndexOf(COMMENT_END, dot.getOffset());
                if (offset >= 0 && dot.getOffset() >= offset &&
                    dot.getOffset() < offset + COMMENT_END.length())
                    pos = new Position(dotLine, offset);
                else if (dotLine.trim().equals(COMMENT_END))
                    pos = new Position(dotLine,
                        dotLine.getText().indexOf(COMMENT_END));
            }
        }

        if (pos == null) {
            editor.status("Nothing to match");
            return;
        }

        Position match = null;
        if (pos.lookingAt(COMMENT_START)) {
            match = findCommentEnd(pos);
        } else if (pos.lookingAt(COMMENT_END)) {
            match = findCommentStart(pos);
        } else if (pos.lookingAt("</")) {
            // End tag.
            String name =
                Utilities.getTagName(pos.getLine().substring(pos.getOffset()));
            name = name.substring(1); // Remove "/".
            match = findMatchingStartTag(name, pos);
        } else if (pos.lookingAt("<")) {
            // Start tag.
            String tag = getTag(pos.copy());
            if (tag.endsWith("/>")) {
                editor.status("Nothing to match (empty-element tag)");
                return;
            }
            String name = Utilities.getTagName(tag);
            match = findMatchingEndTag(name, pos);
        }

        if (match != null) {
            editor.updateDotLine();
            editor.addUndo(SimpleEdit.MOVE);
            dot.moveTo(match);
            editor.updateDotLine();
            editor.moveCaretToDotCol();
        } else
            editor.status("No match");
    }

    public static void xmlInsertMatchingEndTag()
    {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer.needsRenumbering())
            buffer.renumber();
        final Position dot = editor.getDot();
        String tag = getUnmatchedStartTag(dot);
        if (tag != null) {
            final String name = Utilities.getTagName(tag);
            final String endTag = "</" + name + ">";
            try {
                buffer.lockWrite();
            }
            catch (InterruptedException e) {
                Log.error(e);
                return;
            }
            try {
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                editor.fillToCaret();
                editor.addUndo(SimpleEdit.INSERT_STRING);
                editor.insertStringInternal(endTag);
                buffer.modified();
                editor.addUndo(SimpleEdit.MOVE);
                editor.moveCaretToDotCol();
                editor.endCompoundEdit(compoundEdit);
            }
            finally {
                buffer.unlockWrite();
            }
        }
    }

    public static void xmlElectricSlash()
    {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer.needsRenumbering())
            buffer.renumber();
        final Position dot = editor.getDotCopy();
        final int offset = dot.getOffset();
        if (offset > 0 && dot.getLine().charAt(offset-1) == '<') {
            dot.setOffset(offset-1);
            String tag = getUnmatchedStartTag(dot);
            if (tag != null) {
                final String name = Utilities.getTagName(tag);
                final String endTag = "/" + name + ">";
                try {
                    buffer.lockWrite();
                }
                catch (InterruptedException e) {
                    Log.error(e);
                    return;
                }
                try {
                    // We don't need a compound edit here since all the
                    // changes are line edits.
                    editor.fillToCaret();
                    editor.addUndo(SimpleEdit.LINE_EDIT);
                    editor.insertStringInternal(endTag);
                    buffer.modified();
                    editor.moveCaretToDotCol();
                    if (buffer.getBooleanProperty(Property.AUTO_INDENT))
                        editor.indentLine();
                }
                finally {
                    buffer.unlockWrite();
                }
                return;
            }

        }
        // Not electric.
        editor.insertNormalChar('/');
    }

    // Scan backward for "<!--".
    private static Position findCommentStart(Position start)
    {
        Position pos = start.copy();
        do {
            if (pos.lookingAt(COMMENT_START))
                return pos;
        } while (pos.prev());
        // Not found.
        return null;
    }

    // Scan forward for "-->".
    private static Position findCommentEnd(Position start)
    {
        Position pos = start.copy();
        do {
            if (pos.lookingAt(COMMENT_END))
                return pos;
        } while (pos.next());
        // Not found.
        return null;
    }
}
