/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.classLoader;

import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.shrikeBT.Decoder;
import com.ibm.wala.shrikeBT.shrikeCT.CTDecoder;
import com.ibm.wala.shrikeCT.ClassReader;
import com.ibm.wala.shrikeCT.CodeReader;
import com.ibm.wala.shrikeCT.ExceptionsReader;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.shrikeCT.LineNumberTableReader;
import com.ibm.wala.shrikeCT.LocalVariableTableReader;
import com.ibm.wala.shrikeCT.ClassReader.AttrIterator;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.debug.Assertions;

/**
 * 
 * A wrapper around a Shrike object that represents a method
 * 
 * @author sfink
 */
public final class ShrikeCTMethodWrapper extends ShrikeBTMethodWrapper {

  /**
   * The index of this method in the declaring class's method list according to
   * Shrike CT.
   */
  private int shrikeMethodIndex;

  /**
   * JVM-level modifiers for this method a value of -1 means "uninitialized"
   */
  private int modifiers = -1;
  
  private final ClassHierarchy cha;

  public ShrikeCTMethodWrapper(IClass klass, int index) {
    super(klass);
    this.shrikeMethodIndex = index;
    this.cha = klass.getClassHierarchy();
  }

  public byte[] getBytecodes() {
    CodeReader code = getCodeReader();
    if (code == null) {
      return null;
    } else {
      return code.getBytecode();
    }
  }

  protected String getMethodName() throws InvalidClassFileException {
    ClassReader reader = getClassReader();
    return reader.getMethodName(shrikeMethodIndex);
  }

  protected String getMethodSignature() throws InvalidClassFileException {
    ClassReader reader = getClassReader();
    return reader.getMethodType(shrikeMethodIndex);
  }

  protected int getModifiers() {
    if (modifiers == -1) {
      modifiers = getClassReader().getMethodAccessFlags(shrikeMethodIndex);
    }
    return modifiers;
  }

  protected Decoder makeDecoder() {
    CodeReader reader = getCodeReader();
    if (reader == null) {
      return null;
    }
    final Decoder d = new CTDecoder(reader);
    try {
      d.decode();
    } catch (Decoder.InvalidBytecodeException ex) {
      Assertions.UNREACHABLE();
    }
    return d;
  }

  public int getMaxLocals() {
    CodeReader reader = getCodeReader();
    return reader.getMaxLocals();
  }

  public int getMaxStackHeight() {
    CodeReader reader = getCodeReader();
    // note that Shrike returns the maximum index in the zero-indexed stack
    // array.
    // Instead, we want the max number of entries on the stack.
    // So we add 1.
    // Additionally, ShrikeBT may add additional stack entries with
    // Constant instructions. We add an additional 1 to account for this,
    // which seems to handle all ShrikeBT code generation patterns.
    // TODO: ShrikeBT should have a getMaxStack method on Decoder, I think.
    return reader.getMaxStack() + 2;
  }

  public boolean hasExceptionHandler() {
    CodeReader reader = getCodeReader();
    if (reader == null)
      return false;
    int[] handlers = reader.getRawHandlers();
    return handlers != null && handlers.length > 0;
  }

  protected String[] getDeclaredExceptionTypeNames() throws InvalidClassFileException {
    ExceptionsReader reader = getExceptionReader();
    if (reader == null) {
      return null;
    } else {
      return reader.getClasses();
    }
  }

  protected void processDebugInfo(BytecodeInfo bcInfo) throws InvalidClassFileException {
    CodeReader cr = getCodeReader();
    bcInfo.lineNumberMap = LineNumberTableReader.makeBytecodeToSourceMap(cr);
    bcInfo.localVariableMap = LocalVariableTableReader.makeVarMap(cr);
  }


  public String getLocalVariableName(int bcIndex, int localNumber) {
    if (bcInfo == null) {
      try {
        processBytecodes();
      } catch (InvalidClassFileException e) {
        e.printStackTrace();
        Assertions.UNREACHABLE();
      }
    }
    if (Assertions.verifyAssertions) {
      Assertions._assert(bcInfo != null);
    }
    int[][] map = bcInfo.localVariableMap;

    if (localNumber > getMaxLocals()) {
      throw new IllegalArgumentException("illegal local number: " + localNumber + ", method " + getName() + " uses at most "
          + getMaxLocals());
    }

    if (map == null) {
      return null;
    } else {
      int[] localPairs = map[bcIndex];
      int localIndex = localNumber * 2;
      if (localPairs == null || localIndex >= localPairs.length) {
        // no information about the specified local at this program point
        return null;
      }
      int nameIndex = localPairs[localIndex];
      if (nameIndex == 0) {
        return null;
      } else {
        try {
          return getClassReader().getCP().getCPUtf8(nameIndex);
        } catch (InvalidClassFileException e) {
          e.printStackTrace();
          Assertions.UNREACHABLE();
          return null;
        }
      }
    }
  }

  /*
   * TODO: cache for efficiency? (non-Javadoc)
   * 
   * @see com.ibm.wala.classLoader.IMethod#hasLocalVariableTable()
   */
  public boolean hasLocalVariableTable() {
    try {
      ClassReader.AttrIterator iter = new ClassReader.AttrIterator();
      getCodeReader().initAttributeIterator(iter);
      for (; iter.isValid(); iter.advance()) {
        if (iter.getName().equals("LocalVariableTable")) {
          return true;
        }
      }
      return false;
    } catch (InvalidClassFileException e) {
      e.printStackTrace();
      Assertions.UNREACHABLE();
      return false;
    }
  }

  private ClassReader getClassReader() {
    return ((ShrikeCTClassWrapper)getDeclaringClass()).getReader();
  }

  private CodeReader getCodeReader() {
    ClassReader.AttrIterator iter = new AttrIterator();
    getClassReader().initMethodAttributeIterator(shrikeMethodIndex, iter);

    // search for the code attribute
    CodeReader code = null;
    try {
      for (; iter.isValid(); iter.advance()) {
        if (iter.getName().toString().equals("Code")) {
          code = new CodeReader(iter);
          break;
        }
      }
    } catch (InvalidClassFileException e) {
      Assertions.UNREACHABLE();
    }
    return code;
  }

  private ExceptionsReader getExceptionReader() {
    ClassReader.AttrIterator iter = new AttrIterator();
    getClassReader().initMethodAttributeIterator(shrikeMethodIndex, iter);

    // search for the desired attribute
    ExceptionsReader result = null;
    try {
      for (; iter.isValid(); iter.advance()) {
        if (iter.getName().toString().equals("Exceptions")) {
          result = new ExceptionsReader(iter);
          break;
        }
      }
    } catch (InvalidClassFileException e) {
      Assertions.UNREACHABLE();
    }
    return result;
  }

  public TypeReference getReturnType() {
    return getReference().getReturnType();
  }

  public ClassHierarchy getClassHierarchy() {
    return cha;
  }
}
