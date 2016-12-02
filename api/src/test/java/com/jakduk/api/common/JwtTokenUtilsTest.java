package com.jakduk.api.common;

import com.jakduk.api.common.util.JwtTokenUtils;
import com.jakduk.api.configuration.authentication.user.CommonPrincipal;
import com.jakduk.core.common.CoreConst;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ObjectUtils;

/**
 * @author pyohwan
 *         16. 8. 4 오후 9:30
 */

@RunWith(MockitoJUnitRunner.class)
public class JwtTokenUtilsTest {

    @InjectMocks
    private JwtTokenUtils jwtTokenUtils = new JwtTokenUtils();

    private CommonPrincipal commonPrincipal;

    @Before
    public void before() {

        ReflectionTestUtils.setField(jwtTokenUtils, "expiration", 600L);
        ReflectionTestUtils.setField(jwtTokenUtils, "secret", "JakduK11!");

        commonPrincipal = CommonPrincipal.builder()
                .email("test50@test.com")
                .username("test01")
                .id("a1b2c3d4")
                .providerId(CoreConst.ACCOUNT_TYPE.JAKDUK)
                .build();
    }


    @Test
    public void JWT토큰검사() {

        String token = jwtTokenUtils.generateToken(commonPrincipal, null);

        Assert.assertFalse(ObjectUtils.isEmpty(token));
        Assert.assertTrue(jwtTokenUtils.isValidateToken(token));
    }
}
