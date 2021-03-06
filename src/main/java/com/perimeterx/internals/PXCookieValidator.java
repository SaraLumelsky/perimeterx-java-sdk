package com.perimeterx.internals;

import com.perimeterx.internals.cookie.AbstractPXCookie;
import com.perimeterx.internals.cookie.PXCookieFactory;
import com.perimeterx.models.PXContext;
import com.perimeterx.models.configuration.PXConfiguration;
import com.perimeterx.models.exceptions.PXCookieDecryptionException;
import com.perimeterx.models.exceptions.PXException;
import com.perimeterx.models.risk.BlockReason;
import com.perimeterx.models.risk.PassReason;
import com.perimeterx.models.risk.S2SCallReason;
import com.perimeterx.utils.Constants;
import com.perimeterx.utils.PXLogger;

/**
 * PXCookieValidator
 * <p>
 * Created by shikloshi on 07/07/2016.
 */
public class PXCookieValidator {

    private static final PXLogger logger = PXLogger.getLogger(PXCookieValidator.class);

    public static PXCookieValidator getDecoder(String cookieKey) throws PXException {
        try {
            PXCookieValidator cookieValidator = new PXCookieValidator();
            return cookieValidator;
        } catch (Exception e) {
            throw new PXException(e);
        }
    }

    /**
     * Verify cookie and set vid, uuid, score on context
     *
     * @param context - request context, data from cookie will be populated
     * @return S2S call reason according to the result of cookie verification
     */
    public boolean verify(PXConfiguration pxConfiguration, PXContext context) {
        AbstractPXCookie pxCookie = null;

        try {
            if (context.isMobileToken()) {
                String authHeader = context.getHeaders().get(Constants.MOBILE_SDK_HEADER);
                switch (authHeader) {
                    case Constants.MOBILE_ERROR_NO_CONNECTION: {
                        logger.error(PXLogger.LogReason.ERROR_MOBILE_NO_CONNECTION);
                        context.setS2sCallReason(S2SCallReason.MOBILE_SDK_CONNECTION);
                        return false;
                    }
                    case Constants.MOBILE_ERROR_PINNING: {
                        logger.error(PXLogger.LogReason.ERROR_MOBILE_PINNING);
                        context.setS2sCallReason(S2SCallReason.MOBILE_SDK_PINNING);
                        return false;
                    }
                    case Constants.MOBILE_ERROR_NO_COOKIE: {
                        logger.error(PXLogger.LogReason.ERROR_MOBILE_NO_TOKEN);
                        context.setS2sCallReason(S2SCallReason.NO_COOKIE);
                        return false;
                    }
                    default: {
                        if (authHeader.isEmpty()) {
                            logger.error(PXLogger.LogReason.DEBUG_COOKIE_DECRYPTION_FAILED);
                            context.setS2sCallReason(S2SCallReason.INVALID_DECRYPTION);
                            return false;
                        }
                    }
                }
            }

            pxCookie = PXCookieFactory.create(pxConfiguration, context);
            if (pxCookie == null) {
                context.setS2sCallReason(S2SCallReason.NO_COOKIE);
                return false;
            }

            // In case pxCookie will be modified from the outside extracting the cookie on the constructor
            // will fail, we test for null for the cookie before, if its null then we want to set pxCookieOrig
            if (pxCookie.getPxCookie() == null || !pxCookie.deserialize()) {
                context.setS2sCallReason(S2SCallReason.INVALID_DECRYPTION);
                return false;
            }

            context.setRiskCookie(pxCookie);
            context.setVid(pxCookie.getVID());
            context.setUuid(pxCookie.getUUID());
            context.setRiskScore(pxCookie.getScore());
            context.setBlockAction(pxCookie.getBlockAction());
            context.setCookieHmac(pxCookie.getHmac());

            if (pxCookie.isExpired()) {
                logger.debug(PXLogger.LogReason.DEBUG_COOKIE_TLL_EXPIRED, pxCookie.getPxCookie(), System.currentTimeMillis() - pxCookie.getTimestamp());
                context.setS2sCallReason(S2SCallReason.COOKIE_EXPIRED);
                return false;
            }

            if (pxCookie.isHighScore()) {
                context.setBlockReason(BlockReason.COOKIE);
                return true;
            }

            if (!pxCookie.isSecured()) {
                context.setS2sCallReason(S2SCallReason.INVALID_VERIFICATION);
                return false;
            }

            if (context.isSensitiveRoute()) {
                logger.debug(PXLogger.LogReason.DEBUG_S2S_RISK_API_SENSITIVE_ROUTE, context.getUri());
                context.setS2sCallReason(S2SCallReason.SENSITIVE_ROUTE);
                return false;
            }
            context.setPassReason(PassReason.COOKIE);
            context.setS2sCallReason(S2SCallReason.NONE);
            return true;

        } catch (PXException | PXCookieDecryptionException e) {
            logger.error(PXLogger.LogReason.DEBUG_COOKIE_DECRYPTION_FAILED, pxCookie);
            context.setS2sCallReason(S2SCallReason.INVALID_DECRYPTION);
            return false;
        }
    }
}
