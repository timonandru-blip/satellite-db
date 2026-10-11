package com.satdb.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * Комментарий (таблица comments из skills/01-database-design/SKILL.md).
 * Полиморфная связь: table_name + record_id указывают на любую запись
 * (satellite_systems / radio_emissions и др.). Поддержаны вложенные
 * комментарии через self-связь parent_comment_id (1:N к самой себе).
 */
@Entity
@Table(name = "comments", indexes = {
        @Index(name = "idx_comments_record", columnList = "table_name,record_id"),
        @Index(name = "idx_comments_type", columnList = "comment_type"),
        @Index(name = "idx_comments_author", columnList = "created_by"),
        @Index(name = "idx_comments_date", columnList = "created_at")
})
public class Comment extends AuditableEntity {

    /** Тип комментария согласно CHECK (comment_type IN ('TECHNICAL','INFORMATIONAL')). */
    public enum CommentType {
        TECHNICAL, INFORMATIONAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Имя таблицы целевой записи (полиморфная ссылка). */
    @Column(name = "table_name", nullable = false, length = 50)
    private String tableName;

    /** ID целевой записи. */
    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_type", nullable = false, length = 20)
    private CommentType commentType;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** created_by — автор комментария, обязательное (NOT NULL в схеме). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false,
            foreignKey = @ForeignKey(name = "fk_comments_author"))
    private User createdBy;

    /** Родительский комментарий (самосвязь для тредов). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id",
            foreignKey = @ForeignKey(name = "fk_comments_parent"))
    private Comment parentComment;

    /** Дочерние комментарии (1:N parent -> replies). */
    @OneToMany(mappedBy = "parentComment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Comment> childComments = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public CommentType getCommentType() {
        return commentType;
    }

    public void setCommentType(CommentType commentType) {
        this.commentType = commentType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public Comment getParentComment() {
        return parentComment;
    }

    public void setParentComment(Comment parentComment) {
        this.parentComment = parentComment;
    }

    public List<Comment> getChildComments() {
        return childComments;
    }

    public void setChildComments(List<Comment> childComments) {
        this.childComments = childComments;
    }

    /** Вспомогательный метод для поддержания двусторонней связи треда. */
    public void addChildComment(Comment reply) {
        childComments.add(reply);
        reply.setParentComment(this);
    }

    public void removeChildComment(Comment reply) {
        childComments.remove(reply);
        reply.setParentComment(null);
    }
}
