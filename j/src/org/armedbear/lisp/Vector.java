/*
 * Vector.java
 *
 * Copyright (C) 2002-2003 Peter Graves
 * $Id: Vector.java,v 1.8 2003-02-27 18:32:04 piso Exp $
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

public class Vector extends AbstractVector implements SequenceType, VectorType
{
    private final LispObject[] elements;
    private final int length;

    public Vector(int length)
    {
        elements = new LispObject[length];
        for (int i = length; i-- > 0;)
            elements[i] = NIL;
        this.length = length;
    }

    public Vector(LispObject list) throws LispError
    {
        elements = list.copyToArray();
        length = elements.length;
    }

    public Vector(LispObject[] array)
    {
        elements = array;
        length = array.length;
    }

    public LispObject typeOf()
    {
        return list(Symbol.VECTOR, T, new Fixnum(length));
    }

    public boolean isSimpleVector()
    {
        return fillPointer < 0;
    }

    public int length()
    {
        return fillPointer >= 0 ? fillPointer : length;
    }

    public LispObject elt(long index) throws LispError
    {
        long limit = fillPointer >= 0 ? fillPointer : length;
        if (index < 0 || index >= limit)
            badIndex(index);
        return elements[(int)index];
    }

    public LispObject remove(LispObject item) throws LispError
    {
        throw new LispError("not implemented");
    }

    public LispObject get(int index)
    {
        return elements[index];
    }

    public void set(int index, LispObject newValue)
    {
        elements[index] = newValue;
    }

    public void fill(LispObject obj) throws LispError
    {
        for (int i = length; i-- > 0;)
            elements[i] = obj;
    }

    public String toString()
    {
        StringBuffer sb = new StringBuffer("#(");
        // FIXME The limit should actually be the value of *PRINT-LENGTH*.
        final int limit = Math.min(length(), 10);
        for (int i = 0; i < limit; i++) {
            if (i > 0)
                sb.append(' ');
            sb.append(elements[i]);
        }
        if (limit < length())
            sb.append(" ...");
        sb.append(')');
        return sb.toString();
    }
}
