package com.jakduk.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.jakduk.model.db.BoardFree;
import com.jakduk.model.simple.BoardFreeOnList;

public interface BoardFreeRepository extends MongoRepository<BoardFree, String> {

	Page<BoardFreeOnList> findByCategoryName(String categoryName, Pageable pageable);
	BoardFree findOneBySeq(Integer seq);
	BoardFree findByWriter(String writer);
	long countByCategoryName(String categoryName);
	long count();
	
	
}
