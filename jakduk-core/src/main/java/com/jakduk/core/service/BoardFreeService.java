package com.jakduk.core.service;

import com.jakduk.core.authentication.common.CommonPrincipal;
import com.jakduk.core.common.CommonConst;
import com.jakduk.core.dao.BoardDAO;
import com.jakduk.core.exception.ServiceError;
import com.jakduk.core.exception.ServiceException;
import com.jakduk.core.exception.UserFeelingException;
import com.jakduk.core.model.db.BoardCategory;
import com.jakduk.core.model.db.BoardFree;
import com.jakduk.core.model.db.BoardFreeComment;
import com.jakduk.core.model.db.Gallery;
import com.jakduk.core.model.embedded.*;
import com.jakduk.core.model.etc.BoardFreeOnBest;
import com.jakduk.core.model.etc.GalleryOnBoard;
import com.jakduk.core.model.simple.BoardFreeOfMinimum;
import com.jakduk.core.model.simple.BoardFreeOnList;
import com.jakduk.core.notification.SlackService;
import com.jakduk.core.repository.BoardFreeCommentRepository;
import com.jakduk.core.repository.BoardFreeOnListRepository;
import com.jakduk.core.repository.BoardFreeRepository;
import com.jakduk.core.repository.GalleryRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BoardFreeService {

	@Autowired
	private BoardFreeRepository boardFreeRepository;

	@Autowired
	private BoardFreeOnListRepository boardFreeOnListRepository;

	@Autowired
	private BoardFreeCommentRepository boardFreeCommentRepository;

	@Autowired
	private GalleryRepository galleryRepository;

	@Autowired
	private BoardDAO boardDAO;

	@Autowired
	private CommonService commonService;

	@Autowired
	private UserService userService;

	@Autowired
	private SearchService searchService;

	@Autowired
	private SlackService slackService;

	public BoardFreeOfMinimum findBoardFreeOfMinimumBySeq(Integer seq) {
		return boardFreeRepository.findBoardFreeOfMinimumBySeq(seq);
	}

	public Integer countCommentsByBoardItem(BoardItem boardItem) {
		return boardFreeCommentRepository.countByBoardItem(boardItem);
	}

	public void saveBoardFree(BoardFree boardFree) {
		boardFreeRepository.save(boardFree);
	}

    /**
     * 자유게시판 글쓰기
     * @param subject 글 제목
     * @param content 글 내용
     * @param categoryCode 글 말머리 Code
     * @param galleries 글과 연동된 사진들
     * @param device 디바이스
     * @return 글 seq
     */
	public Integer insertFreePost(String subject, String content, CommonConst.BOARD_CATEGORY_TYPE categoryCode,
								  List<GalleryOnBoard> galleries, CommonConst.DEVICE_TYPE device) {

		BoardFree boardFree = new BoardFree();

		boardFree.setCategory(categoryCode);
		boardFree.setSubject(subject);
		boardFree.setContent(content);
		boardFree.setViews(0);
		boardFree.setSeq(commonService.getNextSequence(CommonConst.BOARD_TYPE.BOARD_FREE.name()));

		List<BoardImage> galleriesOnBoard = new ArrayList<>();
		galleries.forEach(gallery -> galleriesOnBoard.add(new BoardImage(gallery.getId())));

		if (! galleriesOnBoard.isEmpty())
			boardFree.setGalleries(galleriesOnBoard);

		CommonPrincipal principal = userService.getCommonPrincipal();
		CommonWriter writer = new CommonWriter(principal.getId(), principal.getUsername(), principal.getProviderId());
		boardFree.setWriter(writer);

		BoardStatus boardStatus = new BoardStatus(device);
		boardFree.setStatus(boardStatus);

		List<BoardHistory> histories = new ArrayList<>();
		BoardHistory history = new BoardHistory(new ObjectId().toString(), CommonConst.BOARD_HISTORY_TYPE.CREATE, writer);
		histories.add(history);
		boardFree.setHistory(histories);

		boardFreeRepository.save(boardFree);

		// 글과 연동 된 사진 처리
		BoardItem boardItem = new BoardItem(boardFree.getId(), boardFree.getSeq());

		for (GalleryOnBoard galleryOnBoard : galleries) {

			Optional<Gallery> getGallery = galleryRepository.findOneById(galleryOnBoard.getId());

			if (getGallery.isPresent()) {
				Gallery updateGallery = getGallery.get();

				GalleryStatus status = updateGallery.getStatus();
				List<BoardItem> posts = updateGallery.getPosts();

				if (Objects.isNull(posts))
					posts = new ArrayList<>();

				// 연관된 글이 겹침인지 검사하고, 연관글로 등록한다.
				long itemCount = 0;

				if (! posts.isEmpty()) {
					itemCount = posts.stream()
							.filter(item -> item.getId().equals(boardItem.getId()))
							.count();
				}


				if (itemCount == 0) {
					posts.add(boardItem);
					updateGallery.setPosts(posts);
				}

				if (galleryOnBoard.getName() != null && !galleryOnBoard.getName().isEmpty()) {
					updateGallery.setName(galleryOnBoard.getName());
				} else {
					updateGallery.setName(boardFree.getSubject());
				}

				status.setFrom(CommonConst.GALLERY_FROM_TYPE.BOARD_FREE);
				status.setStatus(CommonConst.GALLERY_STATUS_TYPE.ENABLE);
				updateGallery.setStatus(status);
				galleryRepository.save(updateGallery);

				searchService.createDocumentGallery(updateGallery);
			}
		}

		searchService.createDocumentBoard(boardFree);


		/*
		// 슬랙 알림
		slackService.sendPost(
				boardFree.getWriter().getUsername(),
				boardFree.getSubject(),
				"New post created.",
				UrlUtils.buildFullRequestUrl(
						request.getScheme(),
						request.getServerName(),
						request.getServerPort(),
						request.getContextPath(), null) + "/board/free/" + boardFree.getSeq()
		);
		*/

		if (log.isInfoEnabled()) {
			log.info("new post created. post seq=" + boardFree.getSeq() + ", subject=" + boardFree.getSubject());
		}

		return boardFree.getSeq();
	}

	/**
	 * 자유게시판 글 편집
	 * @param seq 글 seq
	 * @param subject 글 제목
	 * @param content 글 내용
	 * @param categoryCode 글 말머리 Code
	 * @param galleries 글과 연동된 사진들
     * @param device 디바이스
     * @return 글 seq
     */
	public Integer updateFreePost(Integer seq, String subject, String content, CommonConst.BOARD_CATEGORY_TYPE categoryCode,
								  List<GalleryOnBoard> galleries, CommonConst.DEVICE_TYPE device) {

		BoardFree boardFree = boardFreeRepository.findOneBySeq(seq)
				.orElseThrow(() -> new ServiceException(ServiceError.POST_NOT_FOUND));

		CommonPrincipal principal = userService.getCommonPrincipal();
		CommonWriter writer = new CommonWriter(principal.getId(), principal.getUsername(), principal.getProviderId());

		if (! boardFree.getWriter().getUserId().equals(principal.getId()))
			throw new ServiceException(ServiceError.FORBIDDEN);

		boardFree.setSubject(subject);
		boardFree.setContent(content);
		boardFree.setCategory(categoryCode);

		List<BoardImage> galleriesOnBoard = new ArrayList<>();
		galleries.forEach(gallery -> galleriesOnBoard.add(new BoardImage(gallery.getId())));

		if (! galleriesOnBoard.isEmpty())
			boardFree.setGalleries(galleriesOnBoard);

		BoardStatus boardStatus = boardFree.getStatus();

		if (Objects.isNull(boardStatus))
			boardStatus = new BoardStatus();

        boardStatus.setDevice(device);
		boardFree.setStatus(boardStatus);

		List<BoardHistory> histories = boardFree.getHistory();

		if (Objects.isNull(histories))
			histories = new ArrayList<>();

		BoardHistory history = new BoardHistory(new ObjectId().toString(), CommonConst.BOARD_HISTORY_TYPE.EDIT, writer);
		histories.add(history);
		boardFree.setHistory(histories);

		boardFreeRepository.save(boardFree);

		// 글과 연동 된 사진 처리
		BoardItem boardItem = new BoardItem(boardFree.getId(), boardFree.getSeq());

		for (GalleryOnBoard galleryOnBoard : galleries) {

			Optional<Gallery> getGallery = galleryRepository.findOneById(galleryOnBoard.getId());

			if (getGallery.isPresent()) {
				Gallery updateGallery = getGallery.get();

				GalleryStatus status = updateGallery.getStatus();
				List<BoardItem> posts = updateGallery.getPosts();

				if (Objects.isNull(status))
					status = new GalleryStatus();

				if (Objects.isNull(posts))
					posts = new ArrayList<>();

				// 연관된 글이 겹침인지 검사하고, 연관글로 등록한다.
				long itemCount = 0;

				if (! posts.isEmpty())
					itemCount = posts.stream().filter(item -> item.getId().equals(boardItem.getId())).count();

				if (itemCount == 0) {
					posts.add(boardItem);
					updateGallery.setPosts(posts);
				}

				if (galleryOnBoard.getName() != null && !galleryOnBoard.getName().isEmpty()) {
					updateGallery.setName(galleryOnBoard.getName());
				} else {
					updateGallery.setName(boardFree.getSubject());
				}

				status.setFrom(CommonConst.GALLERY_FROM_TYPE.BOARD_FREE);
				status.setStatus(CommonConst.GALLERY_STATUS_TYPE.ENABLE);
				updateGallery.setStatus(status);
				galleryRepository.save(updateGallery);

				searchService.createDocumentGallery(updateGallery);
			}

		}

		searchService.createDocumentBoard(boardFree);

		if (log.isInfoEnabled()) {
			log.info("post was edited. post seq=" + boardFree.getSeq() + ", subject=" + boardFree.getSubject());
		}

		return boardFree.getSeq();
	}

	/**
	 * 자유게시판 글 지움
	 * @param seq 글 seq
	 * @return CommonConst.BOARD_DELETE_TYPE
     */
    public CommonConst.BOARD_DELETE_TYPE deleteFreePost(Integer seq) {

        BoardFree boardFree = boardFreeRepository.findOneBySeq(seq)
				.orElseThrow(() -> new ServiceException(ServiceError.POST_NOT_FOUND));

        CommonPrincipal principal = userService.getCommonPrincipal();
        CommonWriter writer = new CommonWriter(principal.getId(), principal.getUsername(), principal.getProviderId());

        if (! boardFree.getWriter().getUserId().equals(principal.getId()))
            throw new ServiceException(ServiceError.FORBIDDEN);

        BoardItem boardItem = new BoardItem(boardFree.getId(), boardFree.getSeq());

        Integer count = boardFreeCommentRepository.countByBoardItem(boardItem);

        // 글이 지워질 때, 연동된 사진도 끊어주어야 한다.
        // 근데 사진을 지워야 하나 말아야 하는지는 고민해보자. 왜냐하면 연동된 글이 없을수도 있지 않나?
        if (count > 0) {
			boardFree.setContent(null);
			boardFree.setSubject(null);
			boardFree.setWriter(null);

            List<BoardHistory> histories = boardFree.getHistory();

            if (Objects.isNull(histories))
                histories = new ArrayList<>();

            BoardHistory history = new BoardHistory(new ObjectId().toString(), CommonConst.BOARD_HISTORY_TYPE.DELETE, writer);
            histories.add(history);
			boardFree.setHistory(histories);

            BoardStatus boardStatus = boardFree.getStatus();

            if (Objects.isNull(boardStatus))
                boardStatus = new BoardStatus();

            boardStatus.setDelete(true);
			boardFree.setStatus(boardStatus);

            boardFreeRepository.save(boardFree);

            if (log.isInfoEnabled()) {
                log.info("A post was deleted(post only). post seq=" + boardFree.getSeq() + ", subject=" + boardFree.getSubject());
            }
        } else { // 몽땅 지우기.
            boardFreeRepository.delete(boardFree);

            if (log.isInfoEnabled()) {
                log.info("A post was deleted(all). post seq=" + boardFree.getSeq() + ", subject=" + boardFree.getSubject());
            }
        }

        searchService.deleteDocumentBoard(boardFree.getId());

        return count > 0 ? CommonConst.BOARD_DELETE_TYPE.CONTENT : CommonConst.BOARD_DELETE_TYPE.ALL;
    }

	/**
	 * 자유게시판 말머리 목록
	 * @return 말머리 목록
     */
	public List<BoardCategory> getFreeCategories() {

		return boardDAO.getBoardCategories(commonService.getLanguageCode(LocaleContextHolder.getLocale(), null));
	}

	/**
	 * 자유게시판 글 목록
	 * @param category 말머리
	 * @param page 페이지
	 * @param size 크기
     * @return 글 목록
     */
	public Page<BoardFreeOnList> getFreePosts(CommonConst.BOARD_CATEGORY_TYPE category, Integer page, Integer size) {

		Sort sort = new Sort(Sort.Direction.DESC, Collections.singletonList("_id"));
		Pageable pageable = new PageRequest(page - 1, size, sort);
		Page<BoardFreeOnList> posts = null;

		switch (category) {
			case ALL:
				posts = boardFreeOnListRepository.findAll(pageable);
				break;
			case FOOTBALL:
			case FREE:
				posts = boardFreeOnListRepository.findByCategory(category, pageable);
				break;
		}

		return posts;
	}

	/**
	 * 자유 게시판 공지글 목록
     * @return 공지글 목록
     */
	public Page<BoardFreeOnList> getFreeNotices() {

		Sort sort = new Sort(Sort.Direction.DESC, Collections.singletonList("_id"));
		Pageable noticePageable = new PageRequest(0, 10, sort);

		return boardFreeRepository.findByNotice(noticePageable);
	}


	public Optional<BoardFree> getFreePost(int seq) {
		return boardFreeRepository.findOneBySeq(seq);
	}


	// 글 감정 표현.
	public BoardFree setFreeFeelings(Integer seq, CommonConst.FEELING_TYPE feeling) {
		CommonPrincipal principal = userService.getCommonPrincipal();
		String userId = principal.getId();
		String username = principal.getUsername();

		Optional<BoardFree> boardFree = boardFreeRepository.findOneBySeq(seq);
		if (!boardFree.isPresent())
			throw new ServiceException(ServiceError.POST_NOT_FOUND);

		BoardFree getBoardFree = boardFree.get();
		CommonWriter writer = getBoardFree.getWriter();

		List<CommonFeelingUser> usersLiking = getBoardFree.getUsersLiking();
		List<CommonFeelingUser> usersDisliking = getBoardFree.getUsersDisliking();

		if (Objects.isNull(usersLiking)) usersLiking = new ArrayList<>();
		if (Objects.isNull(usersDisliking)) usersDisliking = new ArrayList<>();

		// 이 게시물의 작성자라서 감정 표현을 할 수 없음
		if (userId.equals(writer.getUserId())) {
			throw new UserFeelingException(CommonConst.USER_FEELING_ERROR_CODE.WRITER.toString()
					, commonService.getResourceBundleMessage("messages.common", "common.exception.you.are.writer"));
		}

		// 해당 회원이 좋아요를 이미 했는지 검사
		for (CommonFeelingUser feelingUser : usersLiking) {
			if (Objects.nonNull(feelingUser) && userId.equals(feelingUser.getUserId())) {
				throw new UserFeelingException(CommonConst.USER_FEELING_ERROR_CODE.ALREADY.toString()
						, commonService.getResourceBundleMessage("messages.common", "common.exception.select.already.like"));
			}
		}

		// 해당 회원이 싫어요를 이미 했는지 검사
		for (CommonFeelingUser feelingUser : usersDisliking) {
			if (Objects.nonNull(feelingUser) && userId.equals(feelingUser.getUserId())) {
				throw new UserFeelingException(CommonConst.USER_FEELING_ERROR_CODE.ALREADY.toString()
						, commonService.getResourceBundleMessage("messages.common", "common.exception.select.already.like"));
			}
		}

		CommonFeelingUser feelingUser = new CommonFeelingUser(new ObjectId().toString(), userId, username);

		switch (feeling) {
			case LIKE:
				usersLiking.add(feelingUser);
				getBoardFree.setUsersLiking(usersLiking);
				break;
			case DISLIKE:
				usersDisliking.add(feelingUser);
				getBoardFree.setUsersDisliking(usersDisliking);
				break;
		}

		boardFreeRepository.save(getBoardFree);

		return getBoardFree;
	}

	// 게시판 댓글 추가.
	public BoardFreeComment addFreeComment(Integer seq, String contents, CommonConst.DEVICE_TYPE device) {
		BoardFreeComment boardFreeComment = new BoardFreeComment();

		BoardFreeOfMinimum boardFreeOnComment = boardFreeRepository.findBoardFreeOfMinimumBySeq(seq);

		BoardItem boardItem = new BoardItem(boardFreeOnComment.getId(), boardFreeOnComment.getSeq());
		boardFreeComment.setBoardItem(boardItem);

		CommonPrincipal principal = userService.getCommonPrincipal();
		CommonWriter writer = new CommonWriter(principal.getId(), principal.getUsername(), principal.getProviderId());

		boardFreeComment.setWriter(writer);
		boardFreeComment.setContent(contents);

		BoardCommentStatus status = new BoardCommentStatus();
		status.setDevice(device);
		boardFreeComment.setStatus(status);

		boardFreeCommentRepository.save(boardFreeComment);

		searchService.createDocumentComment(boardFreeComment);

		return boardFreeComment;
	}

	// 게시판 댓글 목록
	public List<BoardFreeComment> getFreeComments(Integer seq, String commentId) {

		List<BoardFreeComment> comments;

		if (! ObjectUtils.isEmpty(commentId)) {
			comments  = boardDAO.getBoardFreeComment(seq, new ObjectId(commentId));
		} else {
			comments  = boardDAO.getBoardFreeComment(seq, null);
		}

		return comments;
	}

	/**
	 * 자유게시판 댓글 감정 표현.
	 * @param commentId 댓글 ID
	 * @param feeling 감정표현 종류
     * @return 자유게시판 댓글 객체
     */
	public BoardFreeComment setFreeCommentFeeling(String commentId, CommonConst.FEELING_TYPE feeling) {

		CommonPrincipal principal = userService.getCommonPrincipal();
		String userId = principal.getId();
		String username = principal.getUsername();

		BoardFreeComment boardComment = boardFreeCommentRepository.findById(commentId);
		CommonWriter writer = boardComment.getWriter();

		List<CommonFeelingUser> usersLiking = boardComment.getUsersLiking();
		List<CommonFeelingUser> usersDisliking = boardComment.getUsersDisliking();

		if (Objects.isNull(usersLiking)) usersLiking = new ArrayList<>();
		if (Objects.isNull(usersDisliking)) usersDisliking = new ArrayList<>();

		// 이 게시물의 작성자라서 감정 표현을 할 수 없음
		if (userId.equals(writer.getUserId())) {
			throw new UserFeelingException(CommonConst.USER_FEELING_ERROR_CODE.WRITER.toString()
					, commonService.getResourceBundleMessage("messages.common", "common.exception.you.are.writer"));
		}

		// 해당 회원이 좋아요를 이미 했는지 검사
		for (CommonFeelingUser feelingUser : usersLiking) {
			if (Objects.nonNull(feelingUser) && userId.equals(feelingUser.getUserId())) {
				throw new UserFeelingException(CommonConst.USER_FEELING_ERROR_CODE.ALREADY.toString()
						, commonService.getResourceBundleMessage("messages.common", "common.exception.select.already.like"));
			}
		}

		// 해당 회원이 싫어요를 이미 했는지 검사
		for (CommonFeelingUser feelingUser : usersDisliking) {
			if (Objects.nonNull(feelingUser) && userId.equals(feelingUser.getUserId())) {
				throw new UserFeelingException(CommonConst.USER_FEELING_ERROR_CODE.ALREADY.toString()
						, commonService.getResourceBundleMessage("messages.common", "common.exception.select.already.like"));
			}
		}

		CommonFeelingUser feelingUser = new CommonFeelingUser(new ObjectId().toString(), userId, username);

		switch (feeling) {
			case LIKE:
				usersLiking.add(feelingUser);
				boardComment.setUsersLiking(usersLiking);
				break;
			case DISLIKE:
				usersDisliking.add(feelingUser);
				boardComment.setUsersDisliking(usersDisliking);
				break;
		}

		boardFreeCommentRepository.save(boardComment);

		return boardComment;
	}

	/**
	 * 자유게시판 글의 공지를 활성화/비활성화 한다.
	 * @param seq 글 seq
	 * @param isEnable 활성화/비활성화
     */
	public void setFreeNotice(int seq, boolean isEnable) {

		Optional<BoardFree> boardFree = boardFreeRepository.findOneBySeq(seq);

		if (!boardFree.isPresent())
			throw new ServiceException(ServiceError.POST_NOT_FOUND);

		CommonPrincipal principal = userService.getCommonPrincipal();
		CommonWriter writer = new CommonWriter(principal.getId(), principal.getUsername(), principal.getProviderId());

		BoardFree getBoardFree = boardFree.get();
		BoardStatus status = getBoardFree.getStatus();

		if (Objects.isNull(status))
			status = new BoardStatus();

		Boolean isNotice = status.getNotice();

		if (Objects.nonNull(isNotice)) {
			if (isEnable && isNotice)
				throw new ServiceException(ServiceError.ALREADY_ENABLE);

			if (! isEnable && ! isNotice)
				throw new ServiceException(ServiceError.ALREADY_DISABLE);
		}

		if (isEnable) {
			status.setNotice(true);
		} else {
			status.setNotice(null);
		}

		getBoardFree.setStatus(status);

		List<BoardHistory> histories = getBoardFree.getHistory();

		if (Objects.isNull(histories))
			histories = new ArrayList<>();

		BoardHistory history = new BoardHistory(new ObjectId().toString(),
				isEnable ? CommonConst.BOARD_HISTORY_TYPE.ENABLE_NOTICE : CommonConst.BOARD_HISTORY_TYPE.DISABLE_NOTICE, writer);
		histories.add(history);

		getBoardFree.setHistory(histories);

		boardFreeRepository.save(getBoardFree);

		if (log.isInfoEnabled())
			log.info("Set notice. post seq=" + getBoardFree.getSeq() + ", type=" + status.getNotice());
	}


	/**
	 * 자유게시판 주간 좋아요수 선두
	 * @return 게시물 목록
     */
	public List<BoardFreeOnBest> getFreeTopLikes() {
		LocalDate date = LocalDate.now().minusWeeks(1);
		Instant instant = date.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant();

		return boardDAO.getBoardFreeCountOfLikeBest(new ObjectId(Date.from(instant)));
	}

	/**
	 * 자유게시판 주간 댓글수 선두
	 * @return 게시물 목록
	 */
	public List<BoardFreeOnBest> getFreeTopComments() {
		LocalDate date = LocalDate.now().minusWeeks(1);
		Instant instant = date.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant();

		HashMap<String, Integer> boardFreeCommentCount = boardDAO.getBoardFreeCountOfCommentBest(new ObjectId(Date.from(instant)));

		ArrayList<ObjectId> commentIds = new ArrayList<>();

		Iterator<?> commentIterator = boardFreeCommentCount.entrySet().iterator();

		// 댓글 많은 글 id 뽑아내기
		while (commentIterator.hasNext()) {
			Entry<String, Integer> entry = (Entry<String, Integer>) commentIterator.next();
			ObjectId objId = new ObjectId(entry.getKey());
			commentIds.add(objId);
		}

		// commentIds를 파라미터로 다시 글을 가져온다.
		List<BoardFreeOnBest> posts = boardDAO.getBoardFreeListOfTop(commentIds);

		for (BoardFreeOnBest boardFree : posts) {
			String id = boardFree.getId();
			Integer count = boardFreeCommentCount.get(id);
			boardFree.setCount(count);
		}

		// sort and limit
		Comparator<BoardFreeOnBest> byCount = (b1, b2) -> b2.getCount() - b1.getCount();
		Comparator<BoardFreeOnBest> byView = (b1, b2) -> b2.getViews() - b1.getViews();

		posts = posts.stream()
				.sorted(byCount.thenComparing(byView))
				.limit(CommonConst.BOARD_TOP_LIMIT)
				.collect(Collectors.toList());

		return posts;
	}

	/**
	 * 자유게시판 댓글 목록
	 * @param page
	 * @param size
     * @return
     */
	public Page<BoardFreeComment> getBoardFreeComments(int page, int size) {

		Sort sort = new Sort(Sort.Direction.DESC, Collections.singletonList("_id"));
		Pageable pageable = new PageRequest(page - 1, size, sort);

		return boardFreeCommentRepository.findAll(pageable);
	}
}