-- Migration to support manual merging of duplicate customer profiles
USE omnichannel_pos;

ALTER TABLE customers
  ADD COLUMN merged_into_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  ADD CONSTRAINT fk_customers_merged_into
    FOREIGN KEY (merged_into_id) REFERENCES customers(id)
    ON UPDATE RESTRICT ON DELETE SET NULL;
