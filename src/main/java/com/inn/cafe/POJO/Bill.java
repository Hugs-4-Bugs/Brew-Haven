package com.inn.cafe.POJO;

import com.vladmihalcea.hibernate.type.json.JsonType;
import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.io.Serializable;



@NamedQuery(name = "Bill.getAllBills", query = "select b from Bill b order by id desc")  // here 2nd 'b' is alias for the forst 'b'.

@NamedQuery(name = "Bill.getBillByUserName", query = "select b from Bill b where b.createdBy = :username order by b.id desc")

@Data
@Entity
@DynamicInsert
@DynamicUpdate
@TypeDef(name = "json", typeClass = JsonType.class)
@Table(name = "bill")
public class Bill implements Serializable {
    private static final long serialVersionUID = 1L;


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "name")
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "contactnumber")  // here if you write contactNumber instead of contactnumber then in db column name will like contact_number
    private String contactNumber;

    @Column(name = "paymentmethod")
    private String paymentMethod;

    @Column(name = "total")
    private Integer total;

    /**
     * This entity stores billing information, including:
     * id, uuid, name, email, contactNumber, paymentMethod, total, and productDetails.

     * The `productDetails` field stores a JSON array containing details of all purchased products.
     * For example, if a user purchases 5 products, their details will be stored in this field as JSON.
     */

    @Type(type = "json")
//    @Column(name = "productdetails", columnDefinition = "TEXT")
    @Column(name = "productdetails", columnDefinition = "json")
    private String productDetails;


    @Column(name = "createdby")   // The `createdBy` field records the user who generated the bill.
    private String createdBy;

    /**
     * ADDITIVE (revenue attribution): campaign code applied at checkout, if any.
     * Nullable — existing rows and campaign-less orders keep NULL. Never
     * interpreted by existing flows; only the campaign service reads it.
     */
    @Column(name = "campaign_code", length = 40)
    private String campaignCode;

}
