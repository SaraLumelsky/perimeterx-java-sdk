package com.perimeterx.internal;

import com.perimeterx.api.PXConfiguration;
import com.perimeterx.api.providers.DefaultHostnameProvider;
import com.perimeterx.api.providers.HostnameProvider;
import com.perimeterx.api.providers.IPProvider;
import com.perimeterx.api.providers.RemoteAddressIPProvider;
import com.perimeterx.http.PXClient;
import com.perimeterx.internals.PXS2SValidator;
import com.perimeterx.models.PXContext;
import com.perimeterx.models.exceptions.PXException;
import com.perimeterx.utils.Constants;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import testutils.PXClientMock;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Test {@link PXS2SValidator}
 * <p>
 * Created by shikloshi on 16/07/2016.
 */
@Test
public class PXS2SValidatorTest {

    private HttpServletRequest request;

    private PXContext context;
    private IPProvider ipProvider;
    private HostnameProvider hostnameProvider;
    private PXClient client;

    private PXS2SValidator validator;

    @BeforeMethod
    public void setUp() throws Exception {
        PXConfiguration pxConfig = new PXConfiguration.Builder()
                .appId("APP_ID")
                .authToken("AUTH_123")
                .cookieKey("COOKIE_123")
                .build();
        this.client = new PXClientMock(50, Constants.CAPTCHA_SUCCESS_CODE);
        this.request = new MockHttpServletRequest();
        this.ipProvider = new RemoteAddressIPProvider();
        this.hostnameProvider = new DefaultHostnameProvider();
        this.context = new PXContext(request, this.ipProvider, this.hostnameProvider, pxConfig);
        validator = new PXS2SValidator(this.client, pxConfig);
    }

    @Test
    public void verifyTest() throws PXException, IOException {
        boolean verify = validator.verify(context);
        Assert.assertEquals(context.getRiskScore(), 50);
    }

}
