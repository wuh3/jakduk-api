package com.jakduk.configuration;

import com.jakduk.authentication.common.OAuthProcessingFilter;
import com.jakduk.authentication.jakduk.JakdukDetailsService;
import com.jakduk.authentication.jakduk.JakdukFailureHandler;
import com.jakduk.authentication.jakduk.JakdukSuccessHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.ui.velocity.VelocityEngineFactoryBean;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Created by pyohwan on 16. 4. 6.
 */

@Configuration
@EnableWebSecurity
@ImportResource(value = {
//        "classpath:/security-context.xml",
        "classpath:/config/oauth/oauth-data.xml"})
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    public void configure(WebSecurity web) throws Exception {
        web.ignoring().antMatchers("/resources/**");
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {

        //http.addFilterAfter()

        http
//                .csrf().disable()
                .authorizeRequests()
                    .antMatchers("/check/**", "/logout", "/home/**", "/about/**").permitAll()
                    .antMatchers("/login*", "/user/write").anonymous()
                    .antMatchers("/user/**", "/oauth/**").authenticated()
                    .antMatchers("/board/*/write", "/board/*/edit", "/jakdu/write").hasAnyRole("USER_01", "USER_02", "USER_03")
                    .antMatchers("/admin/**").hasRole("ROOT")
                    .anyRequest().permitAll()
                    .and()
                .formLogin()
                    .loginPage("/login")
                    .usernameParameter("j_username")
                    .passwordParameter("j_password")
                    .successHandler(jakdukSuccessHandler())
                    .failureHandler(jakdukFailureHandler())
                    .and()
                .logout()
                    .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .logoutSuccessUrl("/logout/success")
                    .and()
                .exceptionHandling().accessDeniedPage("/error/denied")
                    .and()
                .sessionManagement().maximumSessions(3).expiredUrl("/error/maxSession");
//                .and().httpBasic();
//                    .and()
//                .apply(new SpringSocialConfigurer());


    }


    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(jakdukDetailsService()).passwordEncoder(passwordEncoder());
        //auth.inMemoryAuthentication().withUser("test06@test.com").password("password").roles("ADMIN");
    }


    /*
    @Bean
    public DaoAuthenticationProvider jakdukAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setPasswordEncoder(passwordEncoder());
        provider.setUserDetailsService(jakdukDetailsService);
        return provider;
    }
    */

    @Bean
    public StandardPasswordEncoder passwordEncoder() {
        return new StandardPasswordEncoder();
    }

    @Bean
    public JakdukSuccessHandler jakdukSuccessHandler() {
        return new JakdukSuccessHandler();
    }

    @Bean
    public JakdukFailureHandler jakdukFailureHandler() {
        return new JakdukFailureHandler();
    }

    @Bean
    public JakdukDetailsService jakdukDetailsService() {
        return new JakdukDetailsService();
    }


}
