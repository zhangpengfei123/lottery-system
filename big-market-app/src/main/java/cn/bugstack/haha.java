package cn.bugstack;

public class haha {
    public static void main(String []args) {
        int[] arr=new int[]{5,8,3,4,5,2,9,1};
        sortquer(arr,0,arr.length-1);
        for (int n : arr) {
            System.out.print(n + " ");
        }
    }
    public static void sortquer(int[] arr,int left,int right){
        if(left >= right){
            return;
        }
        int index=chazhao(arr,left,right);
        sortquer(arr,left,index-1);
        sortquer(arr,index+1,right);
    }
    public static int chazhao(int[] arr,int left,int right){
        int x=arr[left];
        while(left<right){
            while(left<right && arr[right]>=x){
                right--;
            }
            arr[left]=arr[right];
            while(left<right && arr[left]<=x ){
                left++;
            }
            arr[right]=arr[left];
        }
        arr[left]=x;
        return left;
    }

}