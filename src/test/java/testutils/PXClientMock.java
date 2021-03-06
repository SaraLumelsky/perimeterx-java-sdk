package testutils;

import com.perimeterx.http.PXClient;
import com.perimeterx.models.activities.Activity;
import com.perimeterx.models.activities.EnforcerTelemetry;
import com.perimeterx.models.configuration.ModuleMode;
import com.perimeterx.models.configuration.PXDynamicConfiguration;
import com.perimeterx.models.exceptions.PXException;
import com.perimeterx.models.httpmodels.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

/**
 * Mocking PXClient that is usually create an http request to PX servers
 * Can be configured to block or not.
 * <p>
 * Created by shikloshi on 12/07/2016.
 */
public class PXClientMock implements PXClient {

    private final int score;
    private final int captchaReturnStatus;
    private boolean forceChallenge;

    public PXClientMock(int scoreToReturn, int captchaReturnStatus) {
        this.forceChallenge = false;
        this.score = scoreToReturn;
        this.captchaReturnStatus = captchaReturnStatus;
    }

    public PXClientMock(int scoreToReturn, int captchaReturnStatus, boolean forceChallenge) {
        this.forceChallenge = forceChallenge;
        this.score = scoreToReturn;
        this.captchaReturnStatus = captchaReturnStatus;
    }

    @Override
    public RiskResponse riskApiCall(RiskRequest riskRequest) throws PXException, IOException {
        RiskResponse riskResponse = new RiskResponse("uuid", 0, this.score, "c", null);
        if (forceChallenge) {
            riskResponse.setAction("j");
            riskResponse.setActionData(new RiskResponseBody());
            riskResponse.getActionData().setBody("<html><body></body></html>");
        }
        return riskResponse;
    }

    @Override
    public void sendActivity(Activity activity) throws PXException, IOException {
        // noop
    }

    @Override
    public void sendBatchActivities(List<Activity> activities) throws PXException, IOException {
        // noop
    }

    @Override
    public CaptchaResponse sendCaptchaRequest(ResetCaptchaRequest resetCaptchaRequest) throws PXException, IOException {
        return new CaptchaResponse(captchaReturnStatus, "1", "vid", "cid");
    }

    @Override
    public PXDynamicConfiguration getConfigurationFromServer() {
        PXDynamicConfiguration stub = new PXDynamicConfiguration();
        stub.setAppId("stub_app_id");
        stub.setChecksum("stub_checksum");
        stub.setBlockingScore(1000);
        stub.setCookieSecret("stub_cookie_key");
        stub.setS2sTimeout(1500);
        stub.setApiConnectTimeout(1500);
        stub.setSensitiveHeaders(new HashSet<String>());
        stub.setModuleEnabled(false);
        stub.setModuleMode(ModuleMode.BLOCKING);
        return stub;
    }

    @Override
    public void sendEnforcerTelemetry(EnforcerTelemetry enforcerTelemetry) throws PXException, IOException {
        // noop
    }

}
