-- Demo seed data (categories + products) from the verified instance.
-- SAFE: contains NO user accounts (bootstrap the admin via
-- CAFE_BOOTSTRAP_ADMIN_EMAIL/PASSWORD env vars at startup), no bills,
-- no payments. Optional — the app works on an empty database too.


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*M!100616 SET @OLD_NOTE_VERBOSITY=@@NOTE_VERBOSITY, NOTE_VERBOSITY=0 */;

LOCK TABLES `category` WRITE;
/*!40000 ALTER TABLE `category` DISABLE KEYS */;
INSERT INTO `category` VALUES
(1,'Pizza',1),
(2,'Dosa',0),
(3,'Samosa',0),
(4,'Litti-chokha',0),
(5,'Butter-nan',0),
(6,'Fried-Rice',0),
(7,'Roti sabji',0),
(8,'Cold Coffee',0),
(9,'Lassi',0),
(10,'E2E Sweets',1),
(11,'E2E Verified',1);
/*!40000 ALTER TABLE `category` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `product` WRITE;
/*!40000 ALTER TABLE `product` DISABLE KEYS */;
INSERT INTO `product` VALUES
(1,'Veggie Pizza with olives','Veggie Pizza',199,'true',1),
(2,'South Indian Masala Dosa with chutney','Masala Dosa',139,'true',2),
(3,'Spicy Paneer Samosa with chutney','Paneer Samosa',79,'true',3),
(4,'Litti-Chokha with roasted baingan','Litti Chokha',149,'true',4),
(5,'Butter Naan with garlic','Butter Naan',89,'true',5),
(6,'Veggie Fried Rice with mixed veggies','Veg Fried Rice',129,'true',6),
(7,'Tandoori Roti with butter','Tandoori Roti',79,'true',7),
(8,'Cold Coffee with ice cream','Cold Coffee',159,'true',8),
(9,'Spicy Aloo Tikki with chutney','Aloo Tikki',89,'true',2),
(10,'Sweet Lassi with mango flavor','Mango Lassi',69,'true',9),
(11,'probe2','Probe Latte v2',159,'true',1),
(12,'Created by E2E test','E2E Special Latte',149,'true',1),
(14,'E2E loaded brownie','E2E Brownie',189,'true',10),
(15,'','NoDesc Cookie',59,'true',10);
/*!40000 ALTER TABLE `product` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*M!100616 SET NOTE_VERBOSITY=@OLD_NOTE_VERBOSITY */;

