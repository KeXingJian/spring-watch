package com.springwatch;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class LRU<K,V> {

    private final Map<K, Node> map = new HashMap<>();
    private Node head = new Node();
    private final int size;

    private class Node{

        Node next;
        Node pre;
        K key;
        V value;

        public Node(){

        }
        public Node(K key,V value, Node next, Node pre){
            this.key = key;
            this.value = value;
            this.next = next;
            this.pre = pre;
        }
    }

    public LRU(int maxSize) {
        size = maxSize;
    }

    public void removeNode(K key){
        Node node = map.get(key);
        node.pre.next = node.next;
        node.next.pre = node.pre;

    }

    public void put(K key,V value){
        Node has = map.get(key);
        if(has != null){

        }else {

        }
        if (size == map.size()) {

        }
    }

    public int get(int key){
        return 0;
    }

    static void main() {

    }
}
