package crg.api.external.service.impl;

import crg.api.external.service.SlackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SlackServiceImpl implements SlackService {
    @Override
    public void sendLoginSuccess(String username) {

    }

    @Override
    public void sendSearchingDecoder(String decoderNumber) {

    }

    @Override
    public void sendDecoderFound(String decoderNumber, boolean found) {

    }

    @Override
    public void sendReabonnementProgress(String step, String details) {

    }

    @Override
    public void sendValidationStatus(String status, String details) {

    }

    @Override
    public void sendCustomMessage(String message, String emoji) {

    }
}
