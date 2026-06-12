MERGE INTO users KEY(id) VALUES (1, 'zhangsan', '张三', '13800138001', 1, CURRENT_TIMESTAMP);
MERGE INTO users KEY(id) VALUES (2, 'lisi', '李四', '13800138002', 1, CURRENT_TIMESTAMP);
MERGE INTO users KEY(id) VALUES (3, 'wangwu', '王五', '13800138003', 0, CURRENT_TIMESTAMP);

MERGE INTO products KEY(id) VALUES (1, 'iPhone 15 Pro', '手机', 8999.00, 100, 1);
MERGE INTO products KEY(id) VALUES (2, 'MacBook Pro 14', '笔记本', 14999.00, 50, 1);
MERGE INTO products KEY(id) VALUES (3, 'AirPods Pro 2', '耳机', 1899.00, 200, 1);
MERGE INTO products KEY(id) VALUES (4, 'iPad Air 5', '平板', 4799.00, 80, 1);
MERGE INTO products KEY(id) VALUES (5, '小米14 Ultra', '手机', 6499.00, 150, 1);

MERGE INTO orders KEY(id) VALUES (1, 1, 'zhangsan', 8999.00, 'paid', CURRENT_TIMESTAMP, NULL, NULL, NULL, CURRENT_TIMESTAMP);
MERGE INTO orders KEY(id) VALUES (2, 2, 'lisi', 16898.00, 'shipped', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, CURRENT_TIMESTAMP);
MERGE INTO orders KEY(id) VALUES (3, 1, 'zhangsan', 14999.00, 'completed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP);

MERGE INTO order_items KEY(id) VALUES (1, 1, 1, 'iPhone 15 Pro', 1, 8999.00);
MERGE INTO order_items KEY(id) VALUES (2, 2, 3, 'AirPods Pro 2', 2, 1899.00);
MERGE INTO order_items KEY(id) VALUES (3, 2, 4, 'iPad Air 5', 1, 4799.00);
MERGE INTO order_items KEY(id) VALUES (4, 3, 2, 'MacBook Pro 14', 1, 14999.00);
