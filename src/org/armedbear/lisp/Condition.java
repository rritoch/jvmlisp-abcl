/*
 * Condition.java
 *
 * Copyright (C) 2003-2005 Peter Graves
 * $Id: Condition.java,v 1.42 2005-10-23 16:39:49 piso Exp $
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

package org.armedbear.lisp;

public class Condition extends StandardObject
{
    protected String message = null;

    public Condition()
    {
        super(StandardClass.CONDITION);
        Debug.assertTrue(slots.length == 2);
        slots[0] = NIL;
        slots[1] = NIL;
        message = null;
    }

    protected Condition(LispClass cls)
    {
        super(cls);
        Debug.assertTrue(slots.length >= 2);
        slots[0] = NIL;
        slots[1] = NIL;
        message = null;
    }

    public Condition(LispClass cls, int length)
    {
        super(cls, length);
//         super(cls);
//         Debug.assertTrue(length == cls.getLayoutLength());
        message = null;
    }

    public Condition(LispObject initArgs) throws ConditionThrowable
    {
        super(StandardClass.CONDITION);
        Debug.assertTrue(slots.length == 2);
        initialize(initArgs);
    }

    protected void initialize(LispObject initArgs) throws ConditionThrowable
    {
        LispObject control = NIL;
        LispObject arguments = NIL;
        LispObject first, second;
        while (initArgs instanceof Cons) {
            first = initArgs.car();
            initArgs = initArgs.cdr();
            second = initArgs.car();
            initArgs = initArgs.cdr();
            if (first == Keyword.FORMAT_CONTROL)
                control = second;
            else if (first == Keyword.FORMAT_ARGUMENTS)
                arguments = second;
        }
        setFormatControl(control);
        setFormatArguments(arguments);
    }

    public Condition(String message)
    {
        this.message = message;
    }

    public final LispObject getFormatControl() throws ConditionThrowable
    {
        return getInstanceSlotValue(Symbol.FORMAT_CONTROL);
    }

    public final void setFormatControl(LispObject formatControl)
        throws ConditionThrowable
    {
        setInstanceSlotValue(Symbol.FORMAT_CONTROL, formatControl);
    }

    public final void setFormatControl(String s) throws ConditionThrowable
    {
        setFormatControl(new SimpleString(s));
    }

    public final LispObject getFormatArguments() throws ConditionThrowable
    {
        return getInstanceSlotValue(Symbol.FORMAT_ARGUMENTS);
    }

    public final void setFormatArguments(LispObject formatArguments)
        throws ConditionThrowable
    {
        setInstanceSlotValue(Symbol.FORMAT_ARGUMENTS, formatArguments);
    }

    public String getMessage()
    {
        return message;
    }

    public LispObject typeOf()
    {
        LispClass c = getLispClass();
        if (c != null)
            return c.getSymbol();
        return Symbol.CONDITION;
    }

    public LispObject classOf()
    {
        LispClass c = getLispClass();
        if (c != null)
            return c;
        return StandardClass.CONDITION;
    }

    public LispObject typep(LispObject type) throws ConditionThrowable
    {
        if (type == Symbol.CONDITION)
            return T;
        if (type == StandardClass.CONDITION)
            return T;
        return super.typep(type);
    }

    public String getConditionReport() throws ConditionThrowable
    {
        String s = getMessage();
        if (s != null)
            return s;
        LispObject formatControl = getFormatControl();
        if (formatControl != NIL) {
            try {
                return format(formatControl, getFormatArguments());
            }
            catch (Throwable t) {}
        }
        return unreadableString(typeOf().writeToString());
    }

    public String writeToString() throws ConditionThrowable
    {
        final LispThread thread = LispThread.currentThread();
        if (Symbol.PRINT_ESCAPE.symbolValue(thread) == NIL) {
            String s = getMessage();
            if (s != null)
                return s;
            LispObject formatControl = getFormatControl();
            if (formatControl instanceof Function) {
                StringOutputStream stream = new StringOutputStream();
                Symbol.APPLY.execute(formatControl, stream, getFormatArguments());
                return stream.getString().getStringValue();
            }
            if (formatControl instanceof AbstractString) {
                LispObject f = Symbol.FORMAT.getSymbolFunction();
                if (f == null || f instanceof Autoload)
                    return format(formatControl, getFormatArguments());
                return Symbol.APPLY.execute(f, NIL, formatControl, getFormatArguments()).getStringValue();
            }
        }
        final int maxLevel;
        LispObject printLevel = _PRINT_LEVEL_.symbolValue(thread);
        if (printLevel instanceof Fixnum)
            maxLevel = ((Fixnum)printLevel).value;
        else
            maxLevel = Integer.MAX_VALUE;
        LispObject currentPrintLevel =
            _CURRENT_PRINT_LEVEL_.symbolValue(thread);
        int currentLevel = ((Fixnum)currentPrintLevel).value;
        if (currentLevel >= maxLevel)
            return "#";
        return unreadableString(typeOf().writeToString());
    }

    // ### condition-report
    private static final Primitive CONDITION_REPORT =
        new Primitive("condition-report", PACKAGE_SYS, false, "condition")
    {
        public LispObject execute(LispObject arg) throws ConditionThrowable
        {
            try {
                String s = ((Condition)arg).getMessage();
                return s != null ? new SimpleString(s) : NIL;
            }
            catch (ClassCastException e) {
                return signal(new TypeError(arg, Symbol.CONDITION));
            }
        }
    };
}
