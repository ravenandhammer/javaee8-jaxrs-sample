/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.github.hantsy.ee8sample.aggregate;

import com.github.hantsy.ee8sample.domain.Count;
import com.github.hantsy.ee8sample.domain.Post;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import static java.util.stream.Collectors.toList;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.Response;

/**
 *
 * @author hantsy
 */
@Path("edge")
@RequestScoped
public class AggregateResource {
    
    private Client client;
    
    @Inject
    Logger LOG;
    
    @Context
    UriInfo uriInfo;
    
    public AggregateResource() {
    }
    
    @PostConstruct
    public void initialize() {
        client = ClientBuilder.newClient();
    }
    
    @PreDestroy
    public void destroy() {
        client.close();
    }
    
    @Path("posts")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CompletionStage<Response> aggregatedPosts(
        @QueryParam("q") String keyword,
        @QueryParam("limit") @DefaultValue("10") int limit,
        @QueryParam("offset") @DefaultValue("0") int offset
    ) {
        LOG.log(Level.INFO, "query params:{0},{1},{2}", new Object[]{keyword, limit, offset});
        final Queue<String> errors = new ConcurrentLinkedQueue<>();
        CompletionStage<List<Post>> postsStage = client.target(uriInfo.getBaseUriBuilder().path("posts").build())
            .queryParam("q", keyword)
            .queryParam("limit", limit)
            .queryParam("offset", offset)
            .request(MediaType.APPLICATION_JSON)
            .rx()
            .get(new GenericType<List<Post>>() {
            })
            .exceptionally(
                throwable -> {
                    errors.offer("exception when fetching posts: " + throwable.getMessage());
                    LOG.log(Level.INFO, "exception when fetching posts: {0}", throwable.getMessage());
                    return Collections.emptyList();
                }
            );
        
        return postsStage
            .thenCompose(
                posts -> {
                    
                    List<CompletionStage<PostSummary>> postSummariesStage = posts.stream()
                        .map(
                            p -> {
                                CompletionStage<Long> commentsCountStage = client.target(uriInfo.getBaseUriBuilder().path("posts/{slug}/comments/count").build(p.getSlug()))
                                    .request(MediaType.TEXT_PLAIN)
                                    .rx()
                                    .get(Count.class)
                                    .thenApply(Count::getCount)
                                    .exceptionally(
                                        throwable -> {
                                            errors.offer("exception when counting comments of posts: " + throwable.getMessage());
                                             LOG.log(Level.INFO, "exception when counting comments of posts: {0}", throwable.getMessage());
                                            return 0L;
                                        }
                                    );
                                
                                return CompletableFuture.completedFuture(new PostSummary(p))
                                    .thenCombine(commentsCountStage, PostSummary::commentsCount)
                                    .thenApply(ps -> ps.addLink(Link.fromUriBuilder(uriInfo.getBaseUriBuilder()).rel("self").uri("posts/{slug}").build(p.getSlug())))
                                    .thenApply(ps -> ps.addLink(Link.fromUriBuilder(uriInfo.getBaseUriBuilder()).rel("comments").uri("posts/{slug}/comments").build(p.getSlug())));
                            }
                        )
                        .collect(toList());
                    
                    return sequence(postSummariesStage);
                }
            )
            .thenApply(PostSummaryList::new)
            .thenCompose(psl -> {
                CompletionStage<Long> postsCountStage = client.target(uriInfo.getBaseUriBuilder().path("posts/count").build())
                    .queryParam("q", keyword)
                    .request(MediaType.TEXT_PLAIN)
                    .rx()
                    .get(Count.class)
                    .thenApply(Count::getCount)
                    .exceptionally(
                        throwable -> {
                            errors.offer("exception when counting posts: " + throwable.getMessage());
                            LOG.log(Level.INFO, "exception when counting posts: {0}", throwable.getMessage());
                            return 0L;
                        }
                    );
                
                return CompletableFuture.completedFuture(psl)
                    .thenCombine(
                        postsCountStage,
                        (list, pc) -> {
                            return list.metadata(PageMetadata.builder().limit(limit).offset(offset).total(pc).build());
                        }
                    );
                
            })
            .thenApply(ps -> ps.addLink(Link.fromUriBuilder(uriInfo.getBaseUriBuilder()).rel("self").uri("posts").build()))
            .thenApply(ps-> Response.ok(ps).build());
        
    }

//        @Path("{slug}")
//    @GET
//    public CompletionStage getPostBySlug(@PathParam("slug") String slug) {
//
//        return CompletableFuture
//                .supplyAsync(
//                        () -> this.posts.findBySlug(slug)
//                                .map(PostDetails::new)
//                                .map(p -> p.addLink(Link.fromUriBuilder(uriInfo.getBaseUriBuilder()).rel("self").uri("posts/{slug}").build(p.getSlug())))
//                                .map(p -> p.addLink(Link.fromUriBuilder(uriInfo.getBaseUriBuilder()).rel("comments").uri("posts/{slug}/comments").build(p.getSlug())))
//                                .orElseThrow(() -> new PostNotFoundException(slug))
//                )
//                .thenCombineAsync(
//                        CompletableFuture.supplyAsync(() -> this.comments.countByPost(slug)),
//                        (post, cnt) -> {
//                            post.setCommentsCount(cnt);
//                            return post;
//                        }
//                )
//                .thenCombineAsync(
//                        CompletableFuture.supplyAsync(() -> this.favorites.postIsFavorited(slug, "user")),
//                        (post, favorited) -> {
//                            post.setFavorited(favorited);
//                            return post;
//                        }
//                );
//
//    }
    private <T> CompletionStage<List<T>> sequence(final List<CompletionStage<T>> stages) {
        //noinspection SuspiciousToArrayCall
        final CompletableFuture<Void> done = CompletableFuture.allOf(stages.toArray(new CompletableFuture[stages.size()]));
        
        return done.thenApply(v -> stages.stream()
            .map(CompletionStage::toCompletableFuture)
            .map(CompletableFuture::join)
            .collect(Collectors.<T>toList())
        );
    }
}
