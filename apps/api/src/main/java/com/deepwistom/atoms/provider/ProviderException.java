package com.deepwistom.atoms.provider;

import com.deepwistom.atoms.common.ApiException;

/** Provider failure carrying a public error code and whether retrying is meaningful. */
public final class ProviderException extends ApiException {
    public ProviderException(int status, String code, String message, boolean retryable) {
        super(status, code, message, retryable);
    }
}
