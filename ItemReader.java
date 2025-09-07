package com.batch.Custom.Reader;

import QuerydslPagingItemReader.java;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.batch.item.ItemReader;

import static com.discord.bot.entity.QJobPosting.jobPosting;

public class ItemReader {

    private ItemReader() {
        //객체 생성이 필요 없는 클래스
        throw new IllegalStateException("Utility class");
    }

    public static ItemReader<JobPosting> create(
            JPAQueryFactory jpaQueryFactory,
            int pageSize
    ) {
        // 우리가 직접 만든 QuerydslPagingItemReader를 생성하여 반환합니다.
        return new QuerydslPagingItemReader<>(
                jpaQueryFactory,
                // 1. (queryFactory, lastId) 두 개의 파라미터를 받는 람다식으로 변경
                (qf, id) -> {
                    JPAQuery<JobPosting> query = qf.selectFrom(jobPosting)
                            .where(jobPosting.sentToDiscord.eq(false)); // 기본 조건

                    // 2. lastId가 null이 아닐 경우 (두 번째 페이지부터) id > lastId 조건을 추가
                    if (id != null) {
                        query.where(jobPosting.id.gt(id));
                    }

                    return query.orderBy(jobPosting.id.asc());
                },
                // 3. 조회된 JobPosting 객체에서 ID를 추출하는 방법을 람다식으로 전달
                JobPosting::getId,
                pageSize
        );
    }
}
