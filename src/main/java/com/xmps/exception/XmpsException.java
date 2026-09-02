package com.xmps.exception;

/**
 * 业务异常基类
 */
public class XmpsException extends RuntimeException {

    private final int code;

    public XmpsException(int code, String message) {
        super(message);
        this.code = code;
    }

    public XmpsException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
