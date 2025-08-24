package crg.api.external.util;


import crg.api.external.enumeration.ValidationStatus;

public class ValidationResult {
    private final ValidationStatus status;
    private final String message;
    private final int waitTime;

    public ValidationResult(ValidationStatus status, String message, int waitTime) {
        this.status = status;
        this.message = message;
        this.waitTime = waitTime;
    }

    // Getters
    public ValidationStatus getStatus() { return status; }
    public String getMessage() { return message; }
    public int getWaitTime() { return waitTime; }
}