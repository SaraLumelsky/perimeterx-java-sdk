/**
 * Copyright © 2016 PerimeterX, Inc.
 * * Permission is hereby granted, free of charge, to any
 * * person obtaining a copy of this software and associated
 * * documentation files (the "Software"), to deal in the
 * * Software without restriction, including without limitation
 * * the rights to use, copy, modify, merge, publish,
 * * distribute, sublicense, and/or sell copies of the
 * * Software, and to permit persons to whom the Software is
 * * furnished to do so, subject to the following conditions:
 * *
 * * The above copyright notice and this permission notice
 * * shall be included in all copies or substantial portions of
 * * the Software.
 * *
 * * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
 * * KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * * PURPOSE AND NONINFINGEMENT. IN NO EVENT SHALL THE AUTHORS
 * * OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.perimeterx.api;

import com.perimeterx.api.activities.ActivityHandler;
import com.perimeterx.api.activities.BufferedActivityHandler;
import com.perimeterx.api.providers.*;
import com.perimeterx.api.proxy.DefaultReverseProxy;
import com.perimeterx.api.proxy.ReverseProxy;
import com.perimeterx.api.remoteconfigurations.DefaultRemoteConfigManager;
import com.perimeterx.api.remoteconfigurations.RemoteConfigurationManager;
import com.perimeterx.api.remoteconfigurations.TimerConfigUpdater;
import com.perimeterx.api.verificationhandler.DefaultVerificationHandler;
import com.perimeterx.api.verificationhandler.VerificationHandler;
import com.perimeterx.http.PXHttpClient;
import com.perimeterx.internals.PXCaptchaValidator;
import com.perimeterx.internals.PXCookieValidator;
import com.perimeterx.internals.PXS2SValidator;
import com.perimeterx.models.PXContext;
import com.perimeterx.models.activities.UpdateReason;
import com.perimeterx.models.configuration.PXConfiguration;
import com.perimeterx.models.configuration.PXDynamicConfiguration;
import com.perimeterx.models.exceptions.PXException;
import com.perimeterx.models.risk.PassReason;
import com.perimeterx.utils.Constants;
import com.perimeterx.utils.PXCommonUtils;
import com.perimeterx.utils.PXLogger;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponseWrapper;

/**
 * Facade object for - configuring, validating and blocking requests
 * <p>
 * Created by shikloshi on 03/07/2016.
 */
public class PerimeterX {

    private static final PXLogger logger = PXLogger.getLogger(PerimeterX.class);

    private PXConfiguration configuration;
    private PXS2SValidator serverValidator;
    private PXCookieValidator cookieValidator;
    private ActivityHandler activityHandler;
    private PXCaptchaValidator captchaValidator;
    private IPProvider ipProvider;
    private HostnameProvider hostnameProvider;
    private VerificationHandler verificationHandler;
    private CustomParametersProvider customParametersProvider;
    private ReverseProxy reverseProxy;

    private CloseableHttpClient getHttpClient() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(configuration.getMaxConnections());
        cm.setDefaultMaxPerRoute(configuration.getMaxConnectionsPerRoute());
        return HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultHeaders(PXCommonUtils.getDefaultHeaders(configuration.getAuthToken()))
                .build();
    }

    private CloseableHttpAsyncClient getAsyncHttpClient() {
        CloseableHttpAsyncClient closeableHttpAsyncClient = HttpAsyncClients.createDefault();
        closeableHttpAsyncClient.start();
        return closeableHttpAsyncClient;
    }

    private void init(PXConfiguration configuration) throws PXException {
        this.configuration = configuration;
        hostnameProvider = new DefaultHostnameProvider();
        ipProvider = new CombinedIPProvider(configuration);
        PXHttpClient pxClient = PXHttpClient.getInstance(configuration, getAsyncHttpClient(), getHttpClient());
        this.activityHandler = new BufferedActivityHandler(pxClient, this.configuration);

        if (configuration.isRemoteConfigurationEnabled()) {
            RemoteConfigurationManager remoteConfigManager = new DefaultRemoteConfigManager(configuration, pxClient);
            PXDynamicConfiguration initialConfig = remoteConfigManager.getConfiguration();
            if (initialConfig == null) {
                remoteConfigManager.disableModuleOnError();
            } else {
                remoteConfigManager.updateConfiguration(initialConfig);
            }
            TimerConfigUpdater timerConfigUpdater = new TimerConfigUpdater(remoteConfigManager, configuration, activityHandler);
            timerConfigUpdater.schedule();
        }

        this.serverValidator = new PXS2SValidator(pxClient, this.configuration);
        this.captchaValidator = new PXCaptchaValidator(pxClient, configuration);
        this.cookieValidator = PXCookieValidator.getDecoder(this.configuration.getCookieKey());
        this.verificationHandler = new DefaultVerificationHandler(this.configuration, this.activityHandler);
        this.activityHandler.handleEnforcerTelemetryActivity(configuration, UpdateReason.INIT);
        this.reverseProxy = new DefaultReverseProxy(configuration, ipProvider);
    }

    public PerimeterX(PXConfiguration configuration) throws PXException {
        init(configuration);
    }

    public PerimeterX(PXConfiguration configuration, IPProvider ipProvider, HostnameProvider hostnameProvider) throws PXException {
        init(configuration);
        this.ipProvider = ipProvider;
        this.hostnameProvider = hostnameProvider;
    }

    public PerimeterX(PXConfiguration configuration, IPProvider ipProvider) throws PXException {
        init(configuration);
        this.ipProvider = ipProvider;
    }

    public PerimeterX(PXConfiguration configuration, HostnameProvider hostnameProvider) throws PXException {
        init(configuration);
        this.hostnameProvider = hostnameProvider;
    }

    /**
     * Verify http request using cookie or PX server call
     *
     * @param req             - current http call examined by PX
     * @param responseWrapper - response wrapper on which we will set the response according to PX verification.
     * @return PXContext, or null if module is disabled
     * @throws PXException - PXException
     */
    public PXContext pxVerify(HttpServletRequest req, HttpServletResponseWrapper responseWrapper) throws PXException {
        PXContext context = null;
        logger.debug(PXLogger.LogReason.DEBUG_STARTING_REQUEST_VERIFICTION);

        try {
            if (!moduleEnabled()) {
                logger.debug(PXLogger.LogReason.DEBUG_MODULE_DISABLED);
                return null;
            }

            context = new PXContext(req, this.ipProvider, this.hostnameProvider, configuration);

            if (shouldReverseRequest(req, responseWrapper)) {
                context.setFirstPartyRequest(true);
                return context;
            }

            // Remove captcha cookie to prevent re-use
            Cookie cookie = new Cookie(Constants.COOKIE_CAPTCHA_KEY, StringUtils.EMPTY);
            cookie.setMaxAge(0);
            responseWrapper.addCookie(cookie);

            if (captchaValidator.verify(context)) {
                logger.debug(PXLogger.LogReason.DEBUG_CAPTCHA_COOKIE_FOUND);
                context.setVerified(verificationHandler.handleVerification(context, responseWrapper));
                return context;
            }
            logger.debug(PXLogger.LogReason.DEBUG_CAPTCHA_NO_COOKIE);

            boolean cookieVerified = cookieValidator.verify(this.configuration, context);
            logger.debug(PXLogger.LogReason.DEBUG_COOKIE_EVALUATION_FINISHED, context.getRiskScore());
            // Cookie is valid (exists and not expired) so we can block according to it's score
            if (cookieVerified) {
                logger.debug(PXLogger.LogReason.DEBUG_COOKIE_VERSION_FOUND,  context.getCookieVersion());
                context.setVerified(verificationHandler.handleVerification(context, responseWrapper));
                return context;
            }

            // Calls risk_api and populate the data retrieved to the context
            logger.debug(PXLogger.LogReason.DEBUG_COOKIE_MISSING);
            serverValidator.verify(context);
            context.setVerified(verificationHandler.handleVerification(context,responseWrapper));
        } catch (Exception e) {
            logger.error(PXLogger.LogReason.ERROR_COOKIE_EVALUATION_EXCEPTION,  e.getMessage());
            // If any general exception is being thrown, notify in page_request activity
            if (context != null) {
                context.setPassReason(PassReason.ERROR);
                activityHandler.handlePageRequestedActivity(context);
                context.setVerified(true);
            }
        }
        return context;
    }

    private boolean shouldReverseRequest(HttpServletRequest req, HttpServletResponseWrapper res) throws Exception {
        if (reverseProxy.reversePxClient(req, res)) {
            return true;
        }

        if (reverseProxy.reversePxXhr(req, res)) {
            return true;
        }

        return false;
    }

    private boolean moduleEnabled() {
        return this.configuration.isModuleEnabled();
    }

    /**
     * Set activity handler
     *
     * @param activityHandler - new activity handler to use
     */
    public void setActivityHandler(ActivityHandler activityHandler) {
        this.activityHandler = activityHandler;
    }

    /**
     * Set IP Provider
     *
     * @param ipProvider - IP provider that is used to extract ip from request
     */
    public void setIpProvider(IPProvider ipProvider) {
        this.ipProvider = ipProvider;
    }

    /**
     * Set Hostname Provider
     *
     * @param hostnameProvider - Used to extract hostname from request
     */
    public void setHostnameProvider(HostnameProvider hostnameProvider) {
        this.hostnameProvider = hostnameProvider;
    }

    /**
     * Set Set Verification Handler
     *
     * @param verificationHandler - sets the verification handler for user customization
     */
    public void setVerificationHandler(VerificationHandler verificationHandler) {
        this.verificationHandler = verificationHandler;
    }

    public void setCustomParametersProvider(CustomParametersProvider customParametersProvider) {
        this.customParametersProvider = customParametersProvider;
    }
}