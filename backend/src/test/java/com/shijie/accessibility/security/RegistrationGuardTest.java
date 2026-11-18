package com.shijie.accessibility.security;
import com.shijie.accessibility.common.Hashes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
class RegistrationGuardTest {
    @Test void createsImageWithoutExposingAnswer(){var guard=new RegistrationGuard();var request=new MockHttpServletRequest();var result=guard.challenge(request);assertThat(result).containsOnlyKeys("id","image");assertThat(result.get("image")).startsWith("data:image/png;base64,");assertThat(request.getSession().getAttribute("registrationCaptcha")).isNotNull();}
    @Test void bindsCaptchaToSessionAndAllowsOnlyOneAttempt(){var guard=new RegistrationGuard();var request=new MockHttpServletRequest();request.getSession().setAttribute("registrationCaptcha",new RegistrationGuard.Challenge("id",Hashes.sha256("id123AB"),System.currentTimeMillis()+60000));assertThatCode(()->guard.verify(request,"id","123ab")).doesNotThrowAnyException();assertThatThrownBy(()->guard.verify(request,"id","123AB")).isInstanceOf(RuntimeException.class);}
    @Test void rejectsExpiredCaptchaAndRateLimits(){var guard=new RegistrationGuard();var request=new MockHttpServletRequest();request.getSession().setAttribute("registrationCaptcha",new RegistrationGuard.Challenge("id",Hashes.sha256("id123AB"),0));assertThatThrownBy(()->guard.verify(request,"id","123AB")).isInstanceOf(RuntimeException.class);guard.limit("client",1,60000);assertThatThrownBy(()->guard.limit("client",1,60000)).isInstanceOf(RuntimeException.class);}
}
