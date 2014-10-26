package com.jakduk.service;

import java.util.List;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;

import com.jakduk.authentication.common.CommonUserDetails;
import com.jakduk.authentication.common.OAuthPrincipal;
import com.jakduk.authentication.jakduk.JakdukPrincipal;
import com.jakduk.common.CommonConst;
import com.jakduk.model.db.FootballClub;
import com.jakduk.model.db.User;
import com.jakduk.model.embedded.OAuthUser;
import com.jakduk.model.simple.BoardWriter;
import com.jakduk.model.simple.OAuthUserOnLogin;
import com.jakduk.model.simple.UserProfile;
import com.jakduk.model.web.OAuthUserWrite;
import com.jakduk.model.web.UserProfileWrite;
import com.jakduk.model.web.UserWrite;
import com.jakduk.repository.FootballClubRepository;
import com.jakduk.repository.UserRepository;

@Service
public class UserService {
	
	@Autowired
	private CommonService commonService;
	
	@Autowired
	private UserRepository userRepository;
	
	@Autowired
	private FootballClubRepository footballClubRepository;
	
	@Autowired
	private MongoTemplate mongoTemplate;
	
	private Logger logger = Logger.getLogger(this.getClass());
	
	public void create(User user) {
		StandardPasswordEncoder encoder = new StandardPasswordEncoder();
		
		user.setPassword(encoder.encode(user.getPassword()));
		userRepository.save(user);
	}
	
	public List<User> findAll() {
		return userRepository.findAll();
	}
	
	public BoardWriter testFindId(String userid) {
		Query query = new Query();
		query.addCriteria(Criteria.where("email").is(userid));
		
		return mongoTemplate.findOne(query, BoardWriter.class);
	}
	
	public void checkUserWrite(UserWrite userWrite, BindingResult result) {
		
		String pwd = userWrite.getPassword();
		String pwdCfm = userWrite.getPasswordConfirm();
		
		if (this.existEmail(userWrite.getEmail())) {
			result.rejectValue("email", "user.msg.already.email");
		}
		
		if (this.existUsername(userWrite.getUsername())) {
			result.rejectValue("username", "user.msg.already.username");
		}
		
		if (!pwd.equals(pwdCfm)) {
			result.rejectValue("passwordConfirm", "user.msg.password.mismatch");
		}
	}
	
	public Model getUserWrite(Model model, String language) {
		
		List<FootballClub> footballClubs = commonService.getFootballClubs(language);
		
		model.addAttribute("userWrite", new UserWrite());
		model.addAttribute("footballClubs", footballClubs);
		
		return model;
	}
	
	public void userWrite(UserWrite userWrite) {
		User user = new User();
		user.setEmail(userWrite.getEmail());
		user.setUsername(userWrite.getUsername());
		user.setPassword(userWrite.getPassword());
		
		String footballClub = userWrite.getFootballClub();
		
		if (footballClub != null && footballClub != "-1") {
			FootballClub supportFC = footballClubRepository.findById(userWrite.getFootballClub());
			
			user.setSupportFC(supportFC);
		}
		
		user.setAbout(userWrite.getAbout());
		
		this.create(user);
	}
	
	public void oauthUserWrite(OAuthUserOnLogin oauthUserOnLogin) {
		User user = new User();
		user.setUsername(oauthUserOnLogin.getUsername());
		user.setOauthUser(oauthUserOnLogin.getOauthUser());
		
		userRepository.save(user);
	}
	
	public Boolean existEmail(String email) {
		Boolean result = false;
		
		if (userRepository.findOneByEmail(email) != null) result = true;
		
		return result;
	}
	
	public Boolean existUsername(String username) {
		Boolean result = false;
		
		if (userRepository.findOneByUsername(username) != null) result = true;
		
		return result;
	}
	
	public Model getOAuthWriteDetails(Model model, String language) {
		
		List<FootballClub> footballClubs = commonService.getFootballClubs(language);
		
		OAuthPrincipal principal = (OAuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		CommonUserDetails userDetails = (CommonUserDetails) SecurityContextHolder.getContext().getAuthentication().getDetails();
		
		OAuthUserOnLogin oauthUserOnLogin = userRepository.findByOauthUser(principal.getType(), principal.getOauthId());
		
		OAuthUserWrite oauthUserWrite = new OAuthUserWrite();
		
		if (oauthUserOnLogin != null && oauthUserOnLogin.getUsername() != null) {
			oauthUserWrite.setUsername(oauthUserOnLogin.getUsername());
		}
		
		if (userDetails != null && userDetails.getBio() != null) {
			oauthUserWrite.setAbout(userDetails.getBio());
		}
		
		model.addAttribute("OAuthUserWrite", oauthUserWrite);
		model.addAttribute("footballClubs", footballClubs);
		
		return model;
	}
	
	public void oAuthWriteDetails(OAuthUserWrite userWrite) {
		
		OAuthPrincipal principal = (OAuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		Object credentials = SecurityContextHolder.getContext().getAuthentication().getCredentials();
		CommonUserDetails userDetails = (CommonUserDetails) SecurityContextHolder.getContext().getAuthentication().getDetails();
		
		User user = userRepository.userFindByOauthUser(principal.getType(), principal.getOauthId());
		OAuthUser oAuthUser = user.getOauthUser();

		String username = userWrite.getUsername();
		String footballClub = userWrite.getFootballClub();
		String about = userWrite.getAbout();
		
		if (username != null && !username.isEmpty()) {
			user.setUsername(userWrite.getUsername());
		}
		
		if (footballClub != null && !footballClub.isEmpty()) {
			FootballClub supportFC = footballClubRepository.findById(userWrite.getFootballClub());
			
			user.setSupportFC(supportFC);
		}
		
		if (about != null && !about.isEmpty()) {
			user.setAbout(userWrite.getAbout());
		}
		
		oAuthUser.setAddInfoStatus(CommonConst.OAUTH_ADDITIONAL_INFO_STATUS_OK);
		
		user.setOauthUser(oAuthUser);
		
		userRepository.save(user);

		principal.setUsername(userWrite.getUsername());
		principal.setAddInfoStatus(CommonConst.OAUTH_ADDITIONAL_INFO_STATUS_OK);
		
		commonService.doAutoLogin(principal, credentials, userDetails);
	}
	
	public Model getUserProfile(Model model, String language) {

		// OAuth 회원이 아닌, 작두왕 회원일 경우다. 그냥 이거는 테스트 용이고 나중에는 OAuth 전체 (페이스북, 다음)과 작두왕 회원에 대한 통합 Principal이 필요.
		JakdukPrincipal authUser = (JakdukPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		UserProfile user = userRepository.findById(authUser.getId());
		
		model.addAttribute("user", user);
		
		return model;
	}
	
	public Model getUserProfileUpdate(Model model, String language) {

		if (SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
			
			List<FootballClub> footballClubs = commonService.getFootballClubs(language);
			
			// OAuth 회원이 아닌, 작두왕 회원일 경우다. 그냥 이거는 테스트 용이고 나중에는 OAuth 전체 (페이스북, 다음)과 작두왕 회원에 대한 통합 Principal이 필요.
			JakdukPrincipal authUser = (JakdukPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
			UserProfile userProfile = userRepository.findById(authUser.getId());
			
			FootballClub footballClub = userProfile.getSupportFC();
			
			UserProfileWrite userProfileWrite = new UserProfileWrite();
			
			userProfileWrite.setEmail(userProfile.getEmail());
			userProfileWrite.setUsername(userProfile.getUsername());

			if (footballClub != null) {
				userProfileWrite.setFootballClub(footballClub.getId());
			}
			
			model.addAttribute("userProfileWrite", userProfileWrite);
			model.addAttribute("footballClubs", footballClubs);
			
		} else {
		}
		
		return model;
	}
	
	public void userProfileUpdate(UserProfileWrite userProfileWrite) {
		
	}
		
}
