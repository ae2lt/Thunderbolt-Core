package com.moakiee.thunderbolt.ae2.batch;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;

final class IdentityCandidateQueue<T> implements Iterable<T> {
   private final IdentityHashMap<T, IdentityCandidateQueue.Node<T>> active = new IdentityHashMap<>();
   private final IdentityHashMap<T, Boolean> blocked = new IdentityHashMap<>();
   private IdentityCandidateQueue.Node<T> head;
   private IdentityCandidateQueue.Node<T> tail;
   private T preferred;

   IdentityCandidateQueue(Iterable<? extends T> candidates) {
      for (T candidate : candidates) {
         if (candidate != null && !this.active.containsKey(candidate)) {
            IdentityCandidateQueue.Node<T> node = new IdentityCandidateQueue.Node<>(candidate);
            this.active.put(candidate, node);
            if (this.tail == null) {
               this.head = this.tail = node;
            } else {
               this.tail.next = node;
               node.previous = this.tail;
               this.tail = node;
            }
         }
      }
   }

   void block(T candidate) {
      this.blocked.put(candidate, Boolean.TRUE);
      IdentityCandidateQueue.Node<T> node = this.active.remove(candidate);
      if (node != null) {
         if (node.previous != null) {
            node.previous.next = node.next;
         } else {
            this.head = node.next;
         }

         if (node.next != null) {
            node.next.previous = node.previous;
         } else {
            this.tail = node.previous;
         }
      }

      if (this.preferred == candidate) {
         this.preferred = null;
      }
   }

   void markSuccess(T candidate) {
      if (this.active.containsKey(candidate)) {
         this.preferred = candidate;
      }
   }

   boolean isBlocked(T candidate) {
      return this.blocked.containsKey(candidate);
   }

   int activeCount() {
      return this.active.size();
   }

   int blockedCount() {
      return this.blocked.size();
   }

   @Override
   public Iterator<T> iterator() {
      return new Iterator<T>() {
         private final T preferredFirst = IdentityCandidateQueue.this.preferred;
         private boolean preferredVisited;
         private IdentityCandidateQueue.Node<T> cursor = IdentityCandidateQueue.this.head;
         private T next;

         @Override
         public boolean hasNext() {
            while (this.next == null) {
               if (!this.preferredVisited) {
                  this.preferredVisited = true;
                  if (this.preferredFirst != null && IdentityCandidateQueue.this.active.containsKey(this.preferredFirst)) {
                     this.next = this.preferredFirst;
                     break;
                  }
               }

               if (this.cursor == null) {
                  break;
               }

               T candidate = this.cursor.value;
               this.cursor = this.cursor.next;
               if (candidate != this.preferredFirst && IdentityCandidateQueue.this.active.containsKey(candidate)) {
                  this.next = candidate;
               }
            }

            return this.next != null;
         }

         @Override
         public T next() {
            if (!this.hasNext()) {
               throw new NoSuchElementException();
            } else {
               T result = this.next;
               this.next = null;
               return result;
            }
         }
      };
   }

   private static final class Node<T> {
      private final T value;
      private IdentityCandidateQueue.Node<T> previous;
      private IdentityCandidateQueue.Node<T> next;

      private Node(T value) {
         this.value = value;
      }
   }
}
