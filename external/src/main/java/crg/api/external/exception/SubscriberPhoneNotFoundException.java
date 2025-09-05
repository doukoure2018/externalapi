package crg.api.external.exception;

public class SubscriberPhoneNotFoundException extends RuntimeException {
    public SubscriberPhoneNotFoundException(String message) {
        super(message);
    }
}