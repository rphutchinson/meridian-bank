-- ============================================================
-- 12-seed-data.sql
-- Realistic seed data for Meridian Bank
-- ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Date arithmetic throughout
-- ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS for expiry dates
-- ORACLE-DEP [EMBEDDED_SQL:TO_DATE] TO_DATE for literal dates
-- ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB] EMPTY_CLOB() initializer
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- ADDRESSES (30 rows: 5 branch addresses + 25 customer addresses)
-- geo_location is NULL here; the BRANCHES table stores SDO_GEOMETRY
-- in its own LOCATION column for spatial queries.
-- ----------------------------------------------------------------
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'100 N Michigan Ave','Chicago','IL','60601','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'200 Park Ave','New York','NY','10166','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'1 Market St','San Francisco','CA','94105','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'500 N Capital St NW','Washington','DC','20001','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'2000 Post Oak Blvd','Houston','TX','77056','USA',SYSDATE);
-- Customer addresses (no geo data - demonstrates NVL usage in queries)
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'123 Elm St','Springfield','IL','62701','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'456 Oak Ave','Portland','OR','97201','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'789 Pine Rd','Austin','TX','78701','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'321 Maple Dr','Seattle','WA','98101','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'654 Cedar Ln','Denver','CO','80201','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'987 Birch Ct','Boston','MA','02101','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'147 Walnut St','Philadelphia','PA','19101','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'258 Chestnut Ave','Charlotte','NC','28201','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'369 Spruce Blvd','Atlanta','GA','30301','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'741 Hickory Way','Nashville','TN','37201','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'852 Magnolia Dr','Miami','FL','33101','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'963 Poplar St','Minneapolis','MN','55401','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'159 Aspen Rd','Salt Lake City','UT','84101','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'357 Willow Ct','Las Vegas','NV','89101','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'753 Palm Ave','Phoenix','AZ','85001','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'951 Redwood Ln','Sacramento','CA','95801','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'246 Cypress Dr','Tampa','FL','33601','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'468 Juniper Way','Albuquerque','NM','87101','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'579 Fir St','Omaha','NE','68101','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'681 Sycamore Blvd','Louisville','KY','40201','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'793 Hemlock Ct','Richmond','VA','23201','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'824 Dogwood Ave','Columbus','OH','43201','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'915 Sassafras Dr','Kansas City','MO','64101','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'103 Persimmon Rd','Raleigh','NC','27601','USA',SYSDATE);
INSERT INTO MERIDIAN.ADDRESSES (address_id,street,city,state_code,zip_code,country,created_date)
VALUES (MERIDIAN.ADDRESS_SEQ.NEXTVAL,'204 Beech St','Indianapolis','IN','46201','USA',SYSDATE);

-- ----------------------------------------------------------------
-- BRANCHES (5 branches — manager_id backfilled after employees)
-- ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] Oracle Spatial point geometry
-- ----------------------------------------------------------------
INSERT INTO MERIDIAN.BRANCHES (branch_id,branch_name,branch_code,manager_id,location,region,phone,email,address_id,status,created_date)
VALUES (MERIDIAN.BRANCH_SEQ.NEXTVAL,'Meridian Bank Chicago Loop','CHI-001',NULL,
        MDSYS.SDO_GEOMETRY(2001,8307,MDSYS.SDO_POINT_TYPE(-87.6244,41.8857,NULL),NULL,NULL),
        'MIDWEST','312-555-0100','chicago@meridianbank.com',4000,'OPEN',SYSDATE);
INSERT INTO MERIDIAN.BRANCHES (branch_id,branch_name,branch_code,manager_id,location,region,phone,email,address_id,status,created_date)
VALUES (MERIDIAN.BRANCH_SEQ.NEXTVAL,'Meridian Bank New York Midtown','NYC-001',NULL,
        MDSYS.SDO_GEOMETRY(2001,8307,MDSYS.SDO_POINT_TYPE(-73.9740,40.7549,NULL),NULL,NULL),
        'NORTHEAST','212-555-0200','nyc@meridianbank.com',4001,'OPEN',SYSDATE);
INSERT INTO MERIDIAN.BRANCHES (branch_id,branch_name,branch_code,manager_id,location,region,phone,email,address_id,status,created_date)
VALUES (MERIDIAN.BRANCH_SEQ.NEXTVAL,'Meridian Bank San Francisco Financial','SFO-001',NULL,
        MDSYS.SDO_GEOMETRY(2001,8307,MDSYS.SDO_POINT_TYPE(-122.3965,37.7910,NULL),NULL,NULL),
        'WEST','415-555-0300','sf@meridianbank.com',4002,'OPEN',SYSDATE);
INSERT INTO MERIDIAN.BRANCHES (branch_id,branch_name,branch_code,manager_id,location,region,phone,email,address_id,status,created_date)
VALUES (MERIDIAN.BRANCH_SEQ.NEXTVAL,'Meridian Bank Washington DC','WDC-001',NULL,
        MDSYS.SDO_GEOMETRY(2001,8307,MDSYS.SDO_POINT_TYPE(-77.0090,38.8974,NULL),NULL,NULL),
        'SOUTHEAST','202-555-0400','dc@meridianbank.com',4003,'OPEN',SYSDATE);
INSERT INTO MERIDIAN.BRANCHES (branch_id,branch_name,branch_code,manager_id,location,region,phone,email,address_id,status,created_date)
VALUES (MERIDIAN.BRANCH_SEQ.NEXTVAL,'Meridian Bank Houston Energy Corridor','HOU-001',NULL,
        MDSYS.SDO_GEOMETRY(2001,8307,MDSYS.SDO_POINT_TYPE(-95.4619,29.7516,NULL),NULL,NULL),
        'SOUTH','713-555-0500','houston@meridianbank.com',4004,'OPEN',SYSDATE);

-- ----------------------------------------------------------------
-- EMPLOYEES (15 — multi-level hierarchy for CONNECT BY queries)
-- ORACLE-DEP [EMBEDDED_SQL:TO_DATE] TO_DATE for hire dates
-- fk_emp_manager is DEFERRABLE INITIALLY DEFERRED so self-referential
-- inserts work within a single transaction (parent inserted before commit)
-- ----------------------------------------------------------------
SET CONSTRAINTS MERIDIAN.FK_EMP_MANAGER DEFERRED;
-- Level 1: CEO (no manager)
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Margaret','Chen','m.chen@meridianbank.com',NULL,10,
        TO_DATE('2015-01-05','YYYY-MM-DD'),350000,'Chief Executive Officer',1,100,'ACTIVE',SYSDATE);
-- Level 2: VPs (manager = 500)
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Robert','Harrington','r.harrington@meridianbank.com',500,20,
        TO_DATE('2016-03-01','YYYY-MM-DD'),220000,'VP Retail Banking',2,100,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Sandra','Okafor','s.okafor@meridianbank.com',500,30,
        TO_DATE('2016-06-15','YYYY-MM-DD'),210000,'VP Technology',2,101,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'James','Whitfield','j.whitfield@meridianbank.com',500,40,
        TO_DATE('2017-01-10','YYYY-MM-DD'),200000,'VP Finance',2,102,'ACTIVE',SYSDATE);
-- Level 3: Branch Managers (manager = 501)
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Patricia','Nguyen','p.nguyen@meridianbank.com',501,20,
        TO_DATE('2018-04-01','YYYY-MM-DD'),120000,'Branch Manager',3,100,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'David','Morales','d.morales@meridianbank.com',501,20,
        TO_DATE('2018-07-01','YYYY-MM-DD'),115000,'Branch Manager',3,101,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Lisa','Fernandez','l.fernandez@meridianbank.com',501,20,
        TO_DATE('2019-01-15','YYYY-MM-DD'),110000,'Branch Manager',3,102,'ACTIVE',SYSDATE);
-- IT Manager (manager = 502)
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Kevin','Park','k.park@meridianbank.com',502,30,
        TO_DATE('2018-09-01','YYYY-MM-DD'),130000,'IT Manager',3,103,'ACTIVE',SYSDATE);
-- Level 4: Staff
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Jennifer','Walsh','j.walsh@meridianbank.com',504,20,
        TO_DATE('2020-02-01','YYYY-MM-DD'),65000,'Loan Officer',4,100,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Michael','Reyes','m.reyes@meridianbank.com',504,20,
        TO_DATE('2020-05-01','YYYY-MM-DD'),62000,'Personal Banker',4,100,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Angela','Thompson','a.thompson@meridianbank.com',505,20,
        TO_DATE('2021-01-10','YYYY-MM-DD'),60000,'Loan Officer',4,101,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Brian','Kowalski','b.kowalski@meridianbank.com',505,20,
        TO_DATE('2021-03-15','YYYY-MM-DD'),58000,'Teller Supervisor',4,101,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Nicole','Adams','n.adams@meridianbank.com',506,20,
        TO_DATE('2021-06-01','YYYY-MM-DD'),59000,'Personal Banker',4,102,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Carlos','Rivera','c.rivera@meridianbank.com',507,30,
        TO_DATE('2020-08-01','YYYY-MM-DD'),95000,'Senior Developer',4,103,'ACTIVE',SYSDATE);
INSERT INTO MERIDIAN.EMPLOYEES (employee_id,first_name,last_name,email,manager_id,department_id,hire_date,salary,job_title,level_num,branch_id,status,created_date)
VALUES (MERIDIAN.EMPLOYEE_SEQ.NEXTVAL,'Priya','Sharma','p.sharma@meridianbank.com',507,30,
        TO_DATE('2021-11-01','YYYY-MM-DD'),90000,'Database Administrator',4,103,'ACTIVE',SYSDATE);

-- Backfill branch managers
UPDATE MERIDIAN.BRANCHES SET manager_id = 504 WHERE branch_code = 'CHI-001';
UPDATE MERIDIAN.BRANCHES SET manager_id = 505 WHERE branch_code = 'NYC-001';
UPDATE MERIDIAN.BRANCHES SET manager_id = 506 WHERE branch_code = 'SFO-001';
UPDATE MERIDIAN.BRANCHES SET manager_id = 504 WHERE branch_code = 'WDC-001';
UPDATE MERIDIAN.BRANCHES SET manager_id = 505 WHERE branch_code = 'HOU-001';

-- ----------------------------------------------------------------
-- CUSTOMERS (25 rows)
-- ORACLE-DEP [EMBEDDED_SQL:TO_DATE] TO_DATE for date_of_birth
-- phone=NULL on some rows demonstrates NVL usage in queries
-- ----------------------------------------------------------------
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Alice','Johnson','alice.johnson@email.com','555-0101',TO_DATE('1985-03-15','YYYY-MM-DD'),4005,'ACTIVE',742,SYSDATE-730);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Bob','Williams','bob.williams@email.com','555-0102',TO_DATE('1978-07-22','YYYY-MM-DD'),4006,'ACTIVE',810,SYSDATE-1200);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Carol','Davis','carol.davis@email.com','555-0103',TO_DATE('1992-11-08','YYYY-MM-DD'),4007,'ACTIVE',695,SYSDATE-400);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Daniel','Martinez','daniel.martinez@email.com','555-0104',TO_DATE('1965-01-30','YYYY-MM-DD'),4008,'ACTIVE',780,SYSDATE-2000);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Elena','Rodriguez','elena.rodriguez@email.com',NULL,TO_DATE('1990-05-17','YYYY-MM-DD'),4009,'ACTIVE',650,SYSDATE-300);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Frank','Lee','frank.lee@email.com','555-0106',TO_DATE('1982-09-03','YYYY-MM-DD'),4010,'ACTIVE',725,SYSDATE-600);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Grace','Wilson','grace.wilson@email.com','555-0107',TO_DATE('1975-12-25','YYYY-MM-DD'),4011,'ACTIVE',800,SYSDATE-1800);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Henry','Anderson','henry.anderson@email.com','555-0108',TO_DATE('1988-04-12','YYYY-MM-DD'),4012,'ACTIVE',620,SYSDATE-180);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Irene','Taylor','irene.taylor@email.com',NULL,TO_DATE('1995-08-28','YYYY-MM-DD'),4013,'ACTIVE',590,SYSDATE-90);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'John','Thomas','john.thomas@email.com','555-0110',TO_DATE('1970-02-14','YYYY-MM-DD'),4014,'ACTIVE',760,SYSDATE-1500);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Karen','Jackson','karen.jackson@email.com','555-0111',TO_DATE('1983-06-06','YYYY-MM-DD'),4015,'ACTIVE',710,SYSDATE-900);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Larry','White','larry.white@email.com','555-0112',TO_DATE('1960-10-19','YYYY-MM-DD'),4016,'ACTIVE',835,SYSDATE-3000);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Maria','Harris','maria.harris@email.com',NULL,TO_DATE('1997-01-23','YYYY-MM-DD'),4017,'ACTIVE',640,SYSDATE-60);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Nathan','Clark','nathan.clark@email.com','555-0114',TO_DATE('1987-07-04','YYYY-MM-DD'),4018,'ACTIVE',685,SYSDATE-500);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Olivia','Lewis','olivia.lewis@email.com','555-0115',TO_DATE('1993-03-30','YYYY-MM-DD'),4019,'ACTIVE',730,SYSDATE-240);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Paul','Robinson','paul.robinson@email.com','555-0116',TO_DATE('1956-11-11','YYYY-MM-DD'),4020,'ACTIVE',790,SYSDATE-4000);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Quinn','Scott','quinn.scott@email.com','555-0117',TO_DATE('2000-09-09','YYYY-MM-DD'),4021,'ACTIVE',550,SYSDATE-30);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Rachel','Young','rachel.young@email.com',NULL,TO_DATE('1979-04-18','YYYY-MM-DD'),4022,'ACTIVE',755,SYSDATE-800);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Steven','King','steven.king@email.com','555-0119',TO_DATE('1968-12-01','YYYY-MM-DD'),4023,'ACTIVE',820,SYSDATE-2500);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Tina','Wright','tina.wright@email.com','555-0120',TO_DATE('1991-06-14','YYYY-MM-DD'),4024,'ACTIVE',675,SYSDATE-350);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Ulysses','Green','ulysses.green@email.com','555-0121',TO_DATE('1974-08-07','YYYY-MM-DD'),4025,'ACTIVE',705,SYSDATE-700);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Vera','Baker','vera.baker@email.com',NULL,TO_DATE('1986-02-28','YYYY-MM-DD'),4026,'INACTIVE',680,SYSDATE-1100);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Walter','Hall','walter.hall@email.com','555-0123',TO_DATE('1958-05-21','YYYY-MM-DD'),4027,'ACTIVE',855,SYSDATE-3500);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Xena','Allen','xena.allen@email.com','555-0124',TO_DATE('1996-10-10','YYYY-MM-DD'),4028,'ACTIVE',610,SYSDATE-120);
INSERT INTO MERIDIAN.CUSTOMERS (customer_id,first_name,last_name,email,phone,date_of_birth,address_id,status,credit_score,created_date)
VALUES (MERIDIAN.CUSTOMER_SEQ.NEXTVAL,'Yoshi','Nelson','yoshi.nelson@email.com','555-0125',TO_DATE('1989-03-03','YYYY-MM-DD'),4029,'ACTIVE',718,SYSDATE-450);

-- ----------------------------------------------------------------
-- ACCOUNTS (35 rows — mix of types across customers)
-- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(19,4) balance
-- ----------------------------------------------------------------
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002000','CHECKING',  12450.00,'ACTIVE',SYSDATE-730, 1000,100,0.0000, 500.00,SYSDATE-730);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002001','SAVINGS',   35000.00,'ACTIVE',SYSDATE-700, 1000,100,2.5000,   0.00,SYSDATE-700);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002002','CHECKING',   8200.50,'ACTIVE',SYSDATE-500, 1001,101,0.0000,1000.00,SYSDATE-500);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002003','MONEY_MARKET',75000.00,'ACTIVE',SYSDATE-480,1001,101,3.2000,   0.00,SYSDATE-480);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002004','CHECKING',   3100.75,'ACTIVE',SYSDATE-365, 1002,102,0.0000, 250.00,SYSDATE-365);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002005','SAVINGS',   18000.00,'ACTIVE',SYSDATE-340, 1002,102,2.0000,   0.00,SYSDATE-340);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002006','CHECKING',  45000.00,'ACTIVE',SYSDATE-900, 1003,100,0.0000,2000.00,SYSDATE-900);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002007','CD',       100000.00,'ACTIVE',SYSDATE-180, 1003,100,4.5000,   0.00,SYSDATE-180);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002008','CHECKING',   5600.00,'ACTIVE',SYSDATE-200, 1004,103,0.0000,   0.00,SYSDATE-200);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002009','SAVINGS',   22500.00,'ACTIVE',SYSDATE-190, 1004,103,2.5000,   0.00,SYSDATE-190);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002010','CHECKING',  11000.00,'ACTIVE',SYSDATE-450, 1005,104,0.0000, 500.00,SYSDATE-450);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002011','IRA',       55000.00,'ACTIVE',SYSDATE-1200,1005,104,5.0000,   0.00,SYSDATE-1200);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002012','CHECKING',   9800.00,'ACTIVE',SYSDATE-300, 1006,100,0.0000, 750.00,SYSDATE-300);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002013','SAVINGS',   40000.00,'ACTIVE',SYSDATE-290, 1006,100,2.8000,   0.00,SYSDATE-290);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002014','CHECKING',   2300.00,'ACTIVE',SYSDATE-150, 1007,101,0.0000, 300.00,SYSDATE-150);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002015','MONEY_MARKET',28000.00,'ACTIVE',SYSDATE-140,1007,101,3.0000,   0.00,SYSDATE-140);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002016','CHECKING',    450.00,'ACTIVE',SYSDATE-60,  1008,102,0.0000,   0.00,SYSDATE-60);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002017','SAVINGS',    6500.00,'ACTIVE',SYSDATE-55,  1008,102,2.0000,   0.00,SYSDATE-55);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002018','CHECKING',   7200.00,'ACTIVE',SYSDATE-400, 1009,103,0.0000, 500.00,SYSDATE-400);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002019','CD',        50000.00,'ACTIVE',SYSDATE-90,  1009,103,4.2000,   0.00,SYSDATE-90);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002020','CHECKING',  14200.00,'ACTIVE',SYSDATE-800, 1010,104,0.0000,1000.00,SYSDATE-800);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002021','SAVINGS',   92000.00,'ACTIVE',SYSDATE-2000,1011,100,3.1000,   0.00,SYSDATE-2000);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002022','CHECKING',   1800.00,'ACTIVE',SYSDATE-30,  1012,101,0.0000,   0.00,SYSDATE-30);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002023','CHECKING',   5500.00,'ACTIVE',SYSDATE-250, 1013,102,0.0000, 250.00,SYSDATE-250);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002024','MONEY_MARKET',31000.00,'ACTIVE',SYSDATE-600,1014,103,3.5000,   0.00,SYSDATE-600);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002025','IRA',       125000.00,'ACTIVE',SYSDATE-3650,1015,104,5.5000,  0.00,SYSDATE-3650);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002026','CHECKING',    900.00,'INACTIVE',SYSDATE-365,1016,101,0.0000,  0.00,SYSDATE-365);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002027','SAVINGS',   19500.00,'ACTIVE',SYSDATE-700, 1017,102,2.2000,   0.00,SYSDATE-700);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002028','CHECKING',   6700.00,'ACTIVE',SYSDATE-180, 1018,103,0.0000, 750.00,SYSDATE-180);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002029','CD',       200000.00,'ACTIVE',SYSDATE-270, 1019,104,4.8000,   0.00,SYSDATE-270);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002030','CHECKING',   4100.00,'ACTIVE',SYSDATE-210, 1023,102,0.0000, 400.00,SYSDATE-210);
INSERT INTO MERIDIAN.ACCOUNTS (account_id,account_number,account_type,balance,status,open_date,customer_id,branch_id,interest_rate,overdraft_limit,created_date)
VALUES (MERIDIAN.ACCOUNT_SEQ.NEXTVAL,'MB0000002031','MONEY_MARKET',48000.00,'ACTIVE',SYSDATE-400,1024,103,3.3000,  0.00,SYSDATE-400);

-- ----------------------------------------------------------------
-- TRANSACTIONS (60+ rows)
-- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] transaction_date uses SYSTIMESTAMP
-- ORACLE-DEP [EMBEDDED_SQL:SYSDATE] date arithmetic with SYSDATE - N
-- ----------------------------------------------------------------
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2000,3500.00,'CREDIT','Direct deposit - payroll',SYSTIMESTAMP-30,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-30);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2000,125.50,'DEBIT','Grocery store purchase',SYSTIMESTAMP-28,'COMPLETED','POS-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'GROCERY',SYSDATE-28);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2000,89.99,'DEBIT','Electric utility bill',SYSTIMESTAMP-25,'COMPLETED','BILL-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'UTILITIES',SYSDATE-25);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2000,500.00,'TRANSFER','Transfer to savings',SYSTIMESTAMP-20,'COMPLETED','TRF-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'TRANSFER',SYSDATE-20);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2000,3500.00,'CREDIT','Direct deposit - payroll',SYSTIMESTAMP-14,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-14);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2000,220.00,'DEBIT','Restaurant and dining',SYSTIMESTAMP-10,'COMPLETED','POS-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'DINING',SYSDATE-10);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2000,15.00,'FEE','Monthly maintenance fee',SYSTIMESTAMP-5,'COMPLETED','FEE-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'FEES',SYSDATE-5);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2001,500.00,'CREDIT','Transfer from checking',SYSTIMESTAMP-20,'COMPLETED','TRF-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'TRANSFER',SYSDATE-20);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2001,72.92,'INTEREST','Monthly interest credit',SYSTIMESTAMP-1,'COMPLETED','INT-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'INTEREST',SYSDATE-1);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2002,5200.00,'CREDIT','Direct deposit - payroll',SYSTIMESTAMP-7,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-7);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2002,1800.00,'DEBIT','Mortgage payment',SYSTIMESTAMP-5,'COMPLETED','MTG-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'HOUSING',SYSDATE-5);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2002,350.00,'DEBIT','Auto insurance',SYSTIMESTAMP-3,'COMPLETED','INS-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'INSURANCE',SYSDATE-3);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2003,200.00,'INTEREST','Quarterly interest',SYSTIMESTAMP-2,'COMPLETED','INT-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'INTEREST',SYSDATE-2);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2004,2800.00,'CREDIT','Salary deposit',SYSTIMESTAMP-14,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-14);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2004,950.00,'DEBIT','Rent payment',SYSTIMESTAMP-12,'COMPLETED','RNT-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'HOUSING',SYSDATE-12);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2006,8500.00,'CREDIT','Business revenue deposit',SYSTIMESTAMP-20,'COMPLETED','BIZ-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'BUSINESS',SYSDATE-20);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2006,3200.00,'DEBIT','Business expense',SYSTIMESTAMP-15,'COMPLETED','BIZ-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'BUSINESS',SYSDATE-15);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2008,3000.00,'CREDIT','Direct deposit',SYSTIMESTAMP-7,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-7);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2010,4100.00,'CREDIT','Payroll',SYSTIMESTAMP-14,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-14);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2010,680.00,'DEBIT','Car loan payment',SYSTIMESTAMP-10,'COMPLETED','LOAN-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'AUTO',SYSDATE-10);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2012,6000.00,'CREDIT','Payroll',SYSTIMESTAMP-7,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-7);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2012,2500.00,'DEBIT','Mortgage',SYSTIMESTAMP-5,'COMPLETED','MTG-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'HOUSING',SYSDATE-5);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2014,1800.00,'CREDIT','Payroll',SYSTIMESTAMP-14,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-14);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2016,750.00,'CREDIT','Freelance payment',SYSTIMESTAMP-3,'COMPLETED','MISC-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'INCOME',SYSDATE-3);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2018,4500.00,'CREDIT','Payroll',SYSTIMESTAMP-7,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-7);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2020,3800.00,'CREDIT','Payroll',SYSTIMESTAMP-14,'COMPLETED','PAY-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'PAYROLL',SYSDATE-14);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2020,1200.00,'DEBIT','Rent',SYSTIMESTAMP-12,'COMPLETED','RNT-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'HOUSING',SYSDATE-12);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2021,237.50,'INTEREST','Monthly savings interest',SYSTIMESTAMP-1,'COMPLETED','INT-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'INTEREST',SYSDATE-1);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2024,9200.00,'CREDIT','Investment return',SYSTIMESTAMP-30,'COMPLETED','INV-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'INVESTMENT',SYSDATE-30);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2025,750.00,'INTEREST','Annual IRA interest',SYSTIMESTAMP-1,'COMPLETED','INT-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'INTEREST',SYSDATE-1);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2029,1200.00,'INTEREST','CD interest payout',SYSTIMESTAMP-1,'COMPLETED','INT-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'INTEREST',SYSDATE-1);
-- Pending transactions (demonstrates MERGE in BATCH_PKG)
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2000,150.00,'DEBIT','Online purchase pending',SYSTIMESTAMP,'PENDING','POS-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'SHOPPING',SYSDATE);
INSERT INTO MERIDIAN.TRANSACTIONS (transaction_id,account_id,amount,transaction_type,description,transaction_date,status,reference_number,category_code,created_date)
VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL,2002,75.00,'DEBIT','Gas station pending',SYSTIMESTAMP,'PENDING','POS-'||MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL,'AUTO',SYSDATE);

-- ----------------------------------------------------------------
-- CREDIT CARDS (10 cards)
-- ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS for expiry dates
-- ----------------------------------------------------------------
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111111000',2000,1000, 5000, 1250.00,ADD_MONTHS(SYSDATE,36),'ACTIVE', 1250,'STANDARD',SYSDATE-365);
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111112001',2002,1001,25000, 8900.00,ADD_MONTHS(SYSDATE,30),'ACTIVE',15200,'PLATINUM',SYSDATE-730);
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111113002',2004,1002,10000, 3400.00,ADD_MONTHS(SYSDATE,24),'ACTIVE', 3400,'GOLD',    SYSDATE-400);
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111114003',2006,1003,50000,12000.00,ADD_MONTHS(SYSDATE,36),'ACTIVE',22000,'TITANIUM',SYSDATE-1200);
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111115004',2008,1004, 5000,    0.00,ADD_MONTHS(SYSDATE,36),'ACTIVE',  500,'STANDARD',SYSDATE-100);
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111116005',2010,1005,10000, 4500.00,ADD_MONTHS(SYSDATE,18),'ACTIVE', 4500,'GOLD',    SYSDATE-600);
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111117006',2012,1006,25000, 7200.00,ADD_MONTHS(SYSDATE,36),'ACTIVE', 9600,'PLATINUM',SYSDATE-900);
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111118007',2014,1007, 5000, 2100.00,ADD_MONTHS(SYSDATE,12),'ACTIVE', 2100,'STANDARD',SYSDATE-200);
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111119008',2018,1009,10000, 5800.00,ADD_MONTHS(SYSDATE,30),'ACTIVE', 5800,'GOLD',    SYSDATE-500);
INSERT INTO MERIDIAN.CREDIT_CARDS (card_id,card_number,account_id,customer_id,credit_limit,current_balance,expiry_date,status,reward_points,card_type,issued_date)
VALUES (MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL,'4111111111120009',2020,1010, 5000, 1800.00,ADD_MONTHS(SYSDATE,24),'ACTIVE', 1800,'STANDARD',SYSDATE-300);

-- ----------------------------------------------------------------
-- LOAN APPLICATIONS (8 applications)
-- ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB] EMPTY_CLOB() for CLOB initialization
-- ORACLE-DEP [EMBEDDED_SQL:TO_CLOB] TO_CLOB for inline string CLOB
-- ----------------------------------------------------------------
INSERT INTO MERIDIAN.LOAN_APPLICATIONS (application_id,customer_id,loan_type,requested_amount,approved_amount,status,application_date,decision_date,interest_rate,term_months,notes,officer_id,created_date)
VALUES (MERIDIAN.LOAN_APP_SEQ.NEXTVAL,1000,'MORTGAGE',350000,340000,'APPROVED',SYSDATE-90,SYSDATE-60,4.25,360,EMPTY_CLOB(),508,SYSDATE-90);
INSERT INTO MERIDIAN.LOAN_APPLICATIONS (application_id,customer_id,loan_type,requested_amount,approved_amount,status,application_date,decision_date,interest_rate,term_months,notes,officer_id,created_date)
VALUES (MERIDIAN.LOAN_APP_SEQ.NEXTVAL,1001,'AUTO',35000,35000,'FUNDED',SYSDATE-120,SYSDATE-100,5.50,60,EMPTY_CLOB(),510,SYSDATE-120);
INSERT INTO MERIDIAN.LOAN_APPLICATIONS (application_id,customer_id,loan_type,requested_amount,approved_amount,status,application_date,decision_date,interest_rate,term_months,notes,officer_id,created_date)
VALUES (MERIDIAN.LOAN_APP_SEQ.NEXTVAL,1002,'PERSONAL',10000,NULL,'DENIED',SYSDATE-45,SYSDATE-35,NULL,NULL,TO_CLOB('Denied: insufficient credit history. Score 695 below threshold 720.'),508,SYSDATE-45);
INSERT INTO MERIDIAN.LOAN_APPLICATIONS (application_id,customer_id,loan_type,requested_amount,approved_amount,status,application_date,decision_date,interest_rate,term_months,notes,officer_id,created_date)
VALUES (MERIDIAN.LOAN_APP_SEQ.NEXTVAL,1003,'BUSINESS',500000,450000,'APPROVED',SYSDATE-60,SYSDATE-30,6.75,84,EMPTY_CLOB(),510,SYSDATE-60);
INSERT INTO MERIDIAN.LOAN_APPLICATIONS (application_id,customer_id,loan_type,requested_amount,approved_amount,status,application_date,decision_date,interest_rate,term_months,notes,officer_id,created_date)
VALUES (MERIDIAN.LOAN_APP_SEQ.NEXTVAL,1004,'STUDENT',25000,NULL,'UNDER_REVIEW',SYSDATE-10,NULL,NULL,120,EMPTY_CLOB(),508,SYSDATE-10);
INSERT INTO MERIDIAN.LOAN_APPLICATIONS (application_id,customer_id,loan_type,requested_amount,approved_amount,status,application_date,decision_date,interest_rate,term_months,notes,officer_id,created_date)
VALUES (MERIDIAN.LOAN_APP_SEQ.NEXTVAL,1005,'PERSONAL',15000,NULL,'SUBMITTED',SYSDATE-3,NULL,NULL,36,EMPTY_CLOB(),NULL,SYSDATE-3);
INSERT INTO MERIDIAN.LOAN_APPLICATIONS (application_id,customer_id,loan_type,requested_amount,approved_amount,status,application_date,decision_date,interest_rate,term_months,notes,officer_id,created_date)
VALUES (MERIDIAN.LOAN_APP_SEQ.NEXTVAL,1008,'MORTGAGE',425000,NULL,'SUBMITTED',SYSDATE-1,NULL,NULL,360,EMPTY_CLOB(),NULL,SYSDATE-1);
INSERT INTO MERIDIAN.LOAN_APPLICATIONS (application_id,customer_id,loan_type,requested_amount,approved_amount,status,application_date,decision_date,interest_rate,term_months,notes,officer_id,created_date)
VALUES (MERIDIAN.LOAN_APP_SEQ.NEXTVAL,1010,'AUTO',22000,22000,'FUNDED',SYSDATE-200,SYSDATE-185,7.25,48,EMPTY_CLOB(),510,SYSDATE-200);

-- ----------------------------------------------------------------
-- DOCUMENTS (10 documents — EMPTY_CLOB content, text indexed later)
-- ----------------------------------------------------------------
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Account Opening Agreement',EMPTY_CLOB(),'AGREEMENT','text/plain',1000,SYSDATE-730,SYSDATE-730,'ACTIVE');
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Mortgage Pre-Approval Letter',EMPTY_CLOB(),'LETTER','text/plain',1000,SYSDATE-60,SYSDATE-60,'ACTIVE');
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Business Loan Application Package',EMPTY_CLOB(),'APPLICATION','application/pdf',1003,SYSDATE-60,SYSDATE-60,'ACTIVE');
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Annual Statement 2025',EMPTY_CLOB(),'STATEMENT','application/pdf',1001,SYSDATE-90,SYSDATE-90,'ACTIVE');
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Identity Verification Documents',EMPTY_CLOB(),'ID_VERIFY','image/jpeg',1002,SYSDATE-365,SYSDATE-365,'ACTIVE');
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Investment Portfolio Summary',EMPTY_CLOB(),'REPORT','application/pdf',1004,SYSDATE-30,SYSDATE-30,'ACTIVE');
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Terms and Conditions Acknowledgment',EMPTY_CLOB(),'AGREEMENT','text/plain',1005,SYSDATE-600,SYSDATE-600,'ACTIVE');
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Auto Loan Vehicle Appraisal',EMPTY_CLOB(),'APPRAISAL','application/pdf',1001,SYSDATE-120,SYSDATE-120,'ACTIVE');
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Student Loan Enrollment Verification',EMPTY_CLOB(),'VERIFICATION','application/pdf',1004,SYSDATE-10,SYSDATE-10,'ACTIVE');
INSERT INTO MERIDIAN.DOCUMENTS (document_id,title,content,category,mime_type,customer_id,indexed_date,created_date,status)
VALUES (MERIDIAN.DOCUMENT_SEQ.NEXTVAL,'Wire Transfer Authorization Form',EMPTY_CLOB(),'AUTHORIZATION','text/plain',1003,SYSDATE-15,SYSDATE-15,'ACTIVE');

-- ----------------------------------------------------------------
-- SCHEDULED JOBS (5 batch jobs)
-- ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS for next run dates
-- ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC for day boundaries
-- ----------------------------------------------------------------
INSERT INTO MERIDIAN.SCHEDULED_JOBS (job_id,job_name,job_type,schedule_expr,status,last_run_date,next_run_date,last_status,run_count,created_date)
VALUES (MERIDIAN.SCHEDULED_JOB_SEQ.NEXTVAL,'End of Day Processing','END_OF_DAY','FREQ=DAILY;BYHOUR=23;BYMINUTE=0','ACTIVE',SYSDATE-1,SYSDATE,'COMPLETED',365,SYSDATE-365);
INSERT INTO MERIDIAN.SCHEDULED_JOBS (job_id,job_name,job_type,schedule_expr,status,last_run_date,next_run_date,last_status,run_count,created_date)
VALUES (MERIDIAN.SCHEDULED_JOB_SEQ.NEXTVAL,'Monthly Interest Calculation','INTEREST_CALC','FREQ=MONTHLY;BYDAY=1;BYHOUR=2','ACTIVE',ADD_MONTHS(SYSDATE,-1),TRUNC(SYSDATE,'MM'),'COMPLETED',12,SYSDATE-365);
INSERT INTO MERIDIAN.SCHEDULED_JOBS (job_id,job_name,job_type,schedule_expr,status,last_run_date,next_run_date,last_status,run_count,created_date)
VALUES (MERIDIAN.SCHEDULED_JOB_SEQ.NEXTVAL,'Weekly Statement Generation','STMT_GEN','FREQ=WEEKLY;BYDAY=SUN;BYHOUR=4','ACTIVE',SYSDATE-7,SYSDATE+7,'COMPLETED',52,SYSDATE-365);
INSERT INTO MERIDIAN.SCHEDULED_JOBS (job_id,job_name,job_type,schedule_expr,status,last_run_date,next_run_date,last_status,run_count,created_date)
VALUES (MERIDIAN.SCHEDULED_JOB_SEQ.NEXTVAL,'Daily Audit Log Archival','AUDIT_ARCHIVE','FREQ=DAILY;BYHOUR=1;BYMINUTE=0','ACTIVE',SYSDATE-1,SYSDATE,'COMPLETED',365,SYSDATE-365);
INSERT INTO MERIDIAN.SCHEDULED_JOBS (job_id,job_name,job_type,schedule_expr,status,last_run_date,next_run_date,last_status,run_count,created_date)
VALUES (MERIDIAN.SCHEDULED_JOB_SEQ.NEXTVAL,'Oracle Text Index Sync','TEXT_SYNC','FREQ=HOURLY;BYMINUTE=30','ACTIVE',SYSDATE,SYSDATE+1/24,'COMPLETED',8760,SYSDATE-365);

COMMIT;

-- Oracle Text index is created in 14-create-text-indexes.sql (after this script)
-- so there is nothing to sync here.
