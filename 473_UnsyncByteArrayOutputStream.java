/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.xml.security.utils;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A simple Unsynced ByteArrayOutputStream
 * @author raul
 *
 */
public class UnsyncByteArrayOutputStream extends OutputStream  {	

    private static final int INITIAL_SIZE = 8192;
    private static ThreadLocal<byte[]> bufCache = new ThreadLocal<byte[]>() {
        @Override
        protected synchronized byte[] initialValue() {
            return new byte[INITIAL_SIZE];
        }
    };

    private byte[] buf;
    private int size = INITIAL_SIZE;
    private int pos = 0;

    public UnsyncByteArrayOutputStream() {
        buf = (byte[])bufCache.get();
    }

    public void write(byte[] arg0) {
        int newPos = pos + arg0.length;
        if (newPos > size) {
            expandSize(newPos);
        }
        System.arraycopy(arg0, 0, buf, pos, arg0.length);
        pos = newPos;
    }

    public void write(byte[] arg0, int arg1, int arg2) {
        int newPos = pos + arg2;
        if (newPos > size) {
            expandSize(newPos);
        }
        System.arraycopy(arg0, arg1, buf, pos, arg2);
        pos = newPos;
    }

    public void write(int arg0) {		
        int newPos = pos + 1;
        if (newPos > size) {
            expandSize(newPos);
        }
        buf[pos++] = (byte)arg0;		
    }

    public byte[] toByteArray() {
        byte result[] = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    public void reset() {
        pos = 0;
        bufCache.remove();
    }
    
    @Override
    public void close() throws IOException {
        bufCache.remove();
    }

    private void expandSize(int newPos) {
        int newSize = size;
        while (newPos > newSize) {
            newSize = newSize << 2;
        }
        byte newBuf[] = new byte[newSize];
        System.arraycopy(buf, 0, newBuf, 0, pos);
        buf = newBuf;
        size = newSize;
    }
}
